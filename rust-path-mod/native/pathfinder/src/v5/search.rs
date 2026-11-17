//! The A* search itself.
//!
//! A* keeps a list of blocks it could explore next (the "open set") and
//! always takes the one that looks cheapest: the cost to get there so far
//! (`g`) plus a guess of what's left to the goal (`h`). Each block it takes,
//! it tries all the moves from there. When it takes a goal, it follows the
//! `parent` links back to the start to get the route.

use super::moves::{Terrain, DIAGONAL, DIRECTIONS, JUMP, MOVES, SPRINT};
use super::world::*;
use std::collections::HashMap;
use std::time::{Duration, Instant};

/// A zone the route should keep away from (the walker adds these round
/// places it got stuck). The cost fades out toward its edge.
#[derive(Clone, Copy, Debug)]
pub struct Avoid {
    pub pos: Pos,
    pub radius_sq: i32,
    pub max_y: i32,
    pub penalty: f32,
}

pub struct Query<'a> {
    pub starts: &'a [Pos],
    pub goals: &'a [Pos],
    pub max_iterations: usize,
    /// Rotates the order moves are tried in, so a search again after getting
    /// stuck can find a different route of the same cost.
    pub seed: i32,
    /// How high one climb can go: 1 is a normal jump.
    pub max_climb: i32,
    pub avoid: &'a [Avoid],
    pub timeout: Duration,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Status {
    Found,
    NoPath,
    NodeLimit,
    TimedOut,
}

#[derive(Debug)]
pub struct SearchResult {
    pub status: Status,
    pub path: Vec<Pos>,
    pub flags: Vec<i32>,
    pub explored: usize,
    /// Which of the starts the route begins at.
    pub start_index: usize,
    pub signature: i64,
}

// Flags on each block of the route, for the walker (see V5Search.java)
const FEET_IN_FLUID: i32 = 1;
const HEAD_IN_FLUID: i32 = 2;
const LOW_CEILING: i32 = 4;
const NEXT_IS_UP: i32 = 32;
const NEXT_IS_DOWN: i32 = 64;

pub fn node_flags(grid: &Grid, path: &[Pos]) -> Vec<i32> {
    let mut all = Vec::with_capacity(path.len());
    for (i, &p) in path.iter().enumerate() {
        let mut flags = 0;
        if has(grid.flags(p), FLUID) {
            flags |= FEET_IN_FLUID;
        }
        if has(grid.flags(above(p, 1)), FLUID) {
            flags |= HEAD_IN_FLUID;
        }
        if !passable(grid.flags(above(p, 2))) {
            flags |= LOW_CEILING;
        }
        if let Some(next) = path.get(i + 1) {
            if next[1] > p[1] {
                flags |= NEXT_IS_UP;
            }
            if next[1] < p[1] {
                flags |= NEXT_IS_DOWN;
            }
        }
        all.push(flags);
    }
    all
}

/// A hash of the route's blocks. The walker uses it to notice when a search
/// again comes back with the same route, and the tests use it to compare
/// with Java.
pub fn signature(path: &[Pos]) -> i64 {
    let mut hash = -3750763034362895579i64;
    for p in path {
        let block = (p[0] as i64 * 73856093) ^ (p[1] as i64 * 19349663) ^ (p[2] as i64 * 83492791);
        hash = (hash ^ block).wrapping_mul(1099511628211);
    }
    hash
}

/// The guess of how much it costs to get from `p` to the nearest goal:
/// diagonal steps plus straight steps, plus a bit for each block up or down.
fn heuristic(p: Pos, goals: &[Pos], fall_ticks: &[f32; 257]) -> f32 {
    let mut best = f32::INFINITY;
    for g in goals {
        let dx = (p[0] as i64 - g[0] as i64).abs();
        let dz = (p[2] as i64 - g[2] as i64).abs();
        let dy = p[1] as i64 - g[1] as i64;
        let diagonal_steps = dx.min(dz);
        let straight_steps = dx.max(dz) - diagonal_steps;
        let mut h = diagonal_steps as f32 * DIAGONAL + straight_steps as f32 * SPRINT;
        if dy != 0 {
            h += if dy > 0 {
                dy as f32 * (fall_ticks[2] * 0.5) // going down
            } else {
                dy.abs() as f32 * JUMP // going up
            };
            h += dy.abs() as f32 * (SPRINT * 0.35);
        }
        best = best.min(h);
    }
    best
}

fn avoid_cost(p: Pos, zones: &[Avoid]) -> f32 {
    let mut cost = 0.;
    for zone in zones {
        if (p[1] as i64 - zone.pos[1] as i64).abs() > zone.max_y as i64 {
            continue;
        }
        let dx = p[0] as i64 - zone.pos[0] as i64;
        let dz = p[2] as i64 - zone.pos[2] as i64;
        let distance_sq = dx * dx + dz * dz;
        if distance_sq > zone.radius_sq as i64 {
            continue;
        }
        let how_far_out = if zone.radius_sq <= 1 {
            0.
        } else {
            distance_sq as f32 / zone.radius_sq as f32
        };
        cost += zone.penalty * (1. - how_far_out).max(0.2);
    }
    cost
}

const NONE: usize = usize::MAX;

#[derive(Clone)]
struct Node {
    pos: Pos,
    g: f32,
    h: f32,
    f: f32,
    /// Avoid-zone cost for stepping onto this block.
    avoid: f32,
    parent: usize,
    start: usize,
    /// Where this node is in the heap (0 = not in it).
    heap_slot: usize,
}

/// Every block the search has seen, plus the open set as a heap.
///
/// The heap is a 4-ary heap (each parent has 4 children) that starts at
/// index 1 and remembers each node's slot, so a node that's found a cheaper
/// way can be moved up without searching for it. Java's search uses the same
/// heap, which matters because it decides which of two equal routes wins.
struct Nodes<'a> {
    query: &'a Query<'a>,
    fall_ticks: [f32; 257],
    nodes: Vec<Node>,
    by_pos: HashMap<Pos, usize>,
    heap: Vec<usize>,
}

impl<'a> Nodes<'a> {
    fn new(query: &'a Query<'a>, fall_ticks: [f32; 257]) -> Self {
        Nodes {
            query,
            fall_ticks,
            nodes: Vec::with_capacity(4096),
            by_pos: HashMap::with_capacity(4096),
            heap: vec![NONE], // slot 0 is unused
        }
    }

    fn open_is_empty(&self) -> bool {
        self.heap.len() == 1
    }

    /// The node for this block, made the first time it's seen.
    fn node_at(&mut self, p: Pos) -> usize {
        if let Some(&i) = self.by_pos.get(&p) {
            return i;
        }
        // The guess is made 5% bigger, which makes the search a bit greedier
        let h = heuristic(p, self.query.goals, &self.fall_ticks) * 1.05;
        self.nodes.push(Node {
            pos: p,
            g: f32::INFINITY,
            h,
            f: h,
            avoid: avoid_cost(p, self.query.avoid),
            parent: NONE,
            start: NONE,
            heap_slot: 0,
        });
        let i = self.nodes.len() - 1;
        self.by_pos.insert(p, i);
        i
    }

    /// Should node `a` come out of the heap before node `b`?
    fn comes_first(&self, a: usize, b: usize) -> bool {
        let (a_node, b_node) = (&self.nodes[a], &self.nodes[b]);
        if a_node.f != b_node.f {
            return a_node.f < b_node.f;
        }
        if a_node.h != b_node.h {
            return a_node.h < b_node.h;
        }
        if a_node.g != b_node.g {
            return a_node.g > b_node.g;
        }
        a < b
    }

    fn move_up(&mut self, mut slot: usize) {
        let node = self.heap[slot];
        while slot > 1 {
            let parent_slot = (slot - 2) / 4 + 1;
            let parent = self.heap[parent_slot];
            if !self.comes_first(node, parent) {
                break;
            }
            self.heap[slot] = parent;
            self.nodes[parent].heap_slot = slot;
            slot = parent_slot;
        }
        self.heap[slot] = node;
        self.nodes[node].heap_slot = slot;
    }

    /// Add a node to the open set, or move it up if it's already there.
    fn push(&mut self, i: usize) {
        let mut slot = self.nodes[i].heap_slot;
        if slot == 0 {
            slot = self.heap.len();
            self.heap.push(i);
            self.nodes[i].heap_slot = slot;
        }
        self.move_up(slot);
    }

    /// Take the cheapest node out of the open set.
    fn pop(&mut self) -> Option<usize> {
        if self.open_is_empty() {
            return None;
        }
        let best = self.heap[1];
        self.nodes[best].heap_slot = 0;
        let last = self.heap.pop().unwrap();
        if self.heap.len() > 1 {
            // Put the last node at the top and move it down to where it belongs
            let mut slot = 1;
            loop {
                let first_child = (slot - 1) * 4 + 2;
                if first_child >= self.heap.len() {
                    break;
                }
                let last_child = (first_child + 3).min(self.heap.len() - 1);
                let mut child = first_child;
                for c in first_child + 1..=last_child {
                    if self.comes_first(self.heap[c], self.heap[child]) {
                        child = c;
                    }
                }
                let child_node = self.heap[child];
                if !self.comes_first(child_node, last) {
                    break;
                }
                self.heap[slot] = child_node;
                self.nodes[child_node].heap_slot = slot;
                slot = child;
            }
            self.heap[slot] = last;
            self.nodes[last].heap_slot = slot;
        }
        Some(best)
    }

    /// A move from `from` reaches block `to` for `cost`. If that's the
    /// cheapest way to `to` so far, remember it and (re)queue `to`.
    fn offer(&mut self, from: usize, to: Pos, cost: f32) {
        let count = self.nodes.len();
        let next = self.node_at(to);
        let is_new = next == count;
        let g = self.nodes[from].g + cost + self.nodes[next].avoid;
        if is_new || g < self.nodes[next].g {
            let start = self.nodes[from].start;
            let node = &mut self.nodes[next];
            node.parent = from;
            node.start = start;
            node.g = g;
            node.f = g + node.h;
            self.push(next);
        }
    }

    fn route_to(&self, mut i: usize) -> Vec<Pos> {
        let mut path = vec![];
        while i != NONE {
            path.push(self.nodes[i].pos);
            i = self.nodes[i].parent;
        }
        path.reverse();
        path
    }
}

pub fn find(grid: &Grid, query: &Query<'_>) -> SearchResult {
    let started = Instant::now();
    let mut terrain = Terrain::new(grid);
    let mut nodes = Nodes::new(query, terrain.fall_ticks);
    let mut explored = 0;
    let failed = |status, explored| SearchResult {
        status,
        path: vec![],
        flags: vec![],
        explored,
        start_index: 0,
        signature: 0,
    };

    if query.starts.is_empty() || query.goals.is_empty() || query.max_iterations == 0 {
        return failed(Status::NoPath, 0);
    }

    // Every start goes in the open set. Starts after the first cost 250 so
    // the first one is preferred.
    for (index, &p) in query.starts.iter().enumerate() {
        let i = nodes.node_at(p);
        let cost = if index == 0 { 0. } else { 250. };
        if cost < nodes.nodes[i].g {
            let node = &mut nodes.nodes[i];
            node.parent = NONE;
            node.start = index;
            node.g = cost;
            node.f = cost + node.h;
        }
        nodes.push(i);
    }

    let first_move = query.seed.max(0) as usize % MOVES.len();
    while !nodes.open_is_empty() && explored < query.max_iterations {
        // Checking the clock every time would be slow, so every 1024 blocks
        if explored % 1024 == 0 && !query.timeout.is_zero() && started.elapsed() > query.timeout {
            return failed(Status::TimedOut, explored);
        }
        explored += 1;

        let current = nodes.pop().unwrap();
        let p = nodes.nodes[current].pos;
        if query.goals.contains(&p) {
            let path = nodes.route_to(current);
            return SearchResult {
                status: Status::Found,
                flags: node_flags(grid, &path),
                signature: signature(&path),
                path,
                explored,
                start_index: nodes.nodes[current].start,
            };
        }

        for k in 0..MOVES.len() {
            let m = MOVES[(k + first_move) % MOVES.len()];
            if let Some((to, cost)) = terrain.try_move(p, m) {
                nodes.offer(current, to, cost);
            }
        }
        if query.max_climb > 1 {
            for &(dx, dz) in &DIRECTIONS[..4] {
                for height in 2..=query.max_climb.min(32) {
                    if let Some((to, cost)) = terrain.climb(p, dx, dz, height) {
                        nodes.offer(current, to, cost);
                    }
                }
            }
        }
    }

    // Ran out of blocks to explore (no route), or hit the iteration limit
    let status = if nodes.open_is_empty() { Status::NoPath } else { Status::NodeLimit };
    failed(status, explored)
}
