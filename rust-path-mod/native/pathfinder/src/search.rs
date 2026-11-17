//! A* over a bounded Minecraft block snapshot, with exact destinations.

use crate::costs::{Costs, INF_COST};
use crate::grid::Grid;
use crate::moves::{Move, Mover};
use std::cmp::Ordering;
use std::collections::{BinaryHeap, HashMap};
use std::hash::{BuildHasherDefault, Hasher};
use std::time::{Duration, Instant};

/// Multiplicative hasher for already well-mixed u64 grid keys.
#[derive(Default)]
pub struct KeyHasher(u64);

impl Hasher for KeyHasher {
    fn finish(&self) -> u64 {
        self.0
    }
    fn write(&mut self, _: &[u8]) {
        unreachable!("only u64 keys are hashed");
    }
    fn write_u64(&mut self, n: u64) {
        let h = n.wrapping_mul(0x9E37_79B9_7F4A_7C15);
        self.0 = h ^ (h >> 29);
    }
}

pub type FastHash = BuildHasherDefault<KeyHasher>;

pub struct Query {
    pub start: (i32, i32, i32),
    pub goal: (i32, i32, i32),
    pub max_nodes: usize,
    pub max_time: Duration,
    pub max_search_dist: f64,
}

#[derive(Debug, PartialEq)]
pub enum Outcome {
    Found,
    /// Open set exhausted: the goal is unreachable inside the grid.
    NoPath,
    NodeLimit,
    TimedOut,
}

pub struct SearchResult {
    pub outcome: Outcome,
    pub nodes_explored: usize,
    /// Full path on success, otherwise the path to the node closest to the goal.
    pub path: Vec<(i32, i32, i32)>,
}

struct Node {
    x: i32,
    y: i32,
    z: i32,
    g: f64,
    h: f64,
    parent: u32,
}

const NO_PARENT: u32 = u32::MAX;

#[derive(PartialEq)]
struct Open {
    f: f64,
    g: f64,
    idx: u32,
}

impl Eq for Open {}

impl Ord for Open {
    fn cmp(&self, other: &Self) -> Ordering {
        // Min-heap on f; among equal f prefer deeper nodes (larger g).
        other
            .f
            .total_cmp(&self.f)
            .then_with(|| self.g.total_cmp(&other.g))
            .then_with(|| self.idx.cmp(&other.idx))
    }
}

impl PartialOrd for Open {
    fn partial_cmp(&self, other: &Self) -> Option<Ordering> {
        Some(self.cmp(other))
    }
}

/// Horizontal distance is a lower bound; climbing costs are paid by movements.
pub fn heuristic(p: (i32, i32, i32), goal: (i32, i32, i32), sprint: f64) -> f64 {
    let dx = (goal.0 - p.0).abs();
    let dz = (goal.2 - p.2).abs();
    ((dx - dz).abs() as f64 + dx.min(dz) as f64 * std::f64::consts::SQRT_2) * sprint
}

/// A coordinate command means this exact feet position.
pub fn is_at_goal(p: (i32, i32, i32), goal: (i32, i32, i32)) -> bool {
    p == goal
}

fn chebyshev(a: (i32, i32, i32), b: (i32, i32, i32)) -> f64 {
    (a.0 - b.0).abs().max((a.1 - b.1).abs()).max((a.2 - b.2).abs()) as f64
}

pub fn find_path(grid: &Grid, costs: &Costs, q: &Query) -> SearchResult {
    let started = Instant::now();
    let mut mover = Mover::new(grid, costs);
    let mut nodes: Vec<Node> = Vec::with_capacity(q.max_nodes.min(1 << 20));
    let mut index: HashMap<u64, u32, FastHash> =
        HashMap::with_capacity_and_hasher(q.max_nodes.min(1 << 20), Default::default());
    let mut open = BinaryHeap::new();
    let mut moves: Vec<Move> = Vec::with_capacity(16);

    let (sx, sy, sz) = q.start;
    let start_h = heuristic(q.start, q.goal, costs.sprint);
    nodes.push(Node { x: sx, y: sy, z: sz, g: 0.0, h: start_h, parent: NO_PARENT });
    index.insert(grid.key(sx, sy, sz), 0);
    open.push(Open { f: start_h, g: 0.0, idx: 0 });

    let mut explored = 0usize;
    let mut closest = 0u32;

    let outcome = loop {
        let Some(cur) = open.pop() else { break Outcome::NoPath };
        let node = &nodes[cur.idx as usize];
        // Stale heap entry: the node was improved after this was pushed.
        if cur.g > node.g {
            continue;
        }
        if explored >= q.max_nodes {
            break Outcome::NodeLimit;
        }
        // Checking the clock is cheap relative to an expansion, but not free.
        if explored % 256 == 0 && started.elapsed() > q.max_time {
            break Outcome::TimedOut;
        }
        explored += 1;

        let (x, y, z, g) = (node.x, node.y, node.z, node.g);
        if node.h < nodes[closest as usize].h {
            closest = cur.idx;
        }
        if is_at_goal((x, y, z), q.goal) {
            return SearchResult {
                outcome: Outcome::Found,
                nodes_explored: explored,
                path: reconstruct(&nodes, cur.idx),
            };
        }

        mover.expand(x, y, z, &mut moves);
        for m in &moves {
            if !(m.cost.is_finite() && m.cost >= 0.0 && m.cost < INF_COST) {
                continue;
            }
            let p = (m.x, m.y, m.z);
            if chebyshev(p, q.start) + chebyshev(p, q.goal) > q.max_search_dist {
                continue;
            }
            let new_g = g + m.cost;
            let key = grid.key(m.x, m.y, m.z);
            let idx = match index.get(&key) {
                Some(&i) => {
                    if new_g >= nodes[i as usize].g {
                        continue;
                    }
                    i
                }
                None => {
                    let i = nodes.len() as u32;
                    let h = heuristic(p, q.goal, costs.sprint);
                    nodes.push(Node { x: m.x, y: m.y, z: m.z, g: f64::INFINITY, h, parent: NO_PARENT });
                    index.insert(key, i);
                    i
                }
            };
            let n = &mut nodes[idx as usize];
            n.g = new_g;
            n.parent = cur.idx;
            open.push(Open { f: new_g + n.h, g: new_g, idx });
        }
    };

    SearchResult {
        outcome,
        nodes_explored: explored,
        path: if closest != 0 { reconstruct(&nodes, closest) } else { Vec::new() },
    }
}

fn reconstruct(nodes: &[Node], mut idx: u32) -> Vec<(i32, i32, i32)> {
    let mut path = Vec::new();
    while idx != NO_PARENT {
        let n = &nodes[idx as usize];
        path.push((n.x, n.y, n.z));
        idx = n.parent;
    }
    path.reverse();
    path
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::grid::GridBuilder;

    const FLOOR_Y: i32 = 64;

    fn flat(size: i32) -> GridBuilder {
        let mut b = GridBuilder::new((0, 60, 0), (size, 16, size));
        b.fill((0, 60, 0), (size - 1, FLOOR_Y, size - 1), GridBuilder::STONE);
        b
    }

    fn run(b: &GridBuilder, start: (i32, i32, i32), goal: (i32, i32, i32)) -> SearchResult {
        let q = Query {
            start,
            goal,
            max_nodes: 500_000,
            max_time: Duration::from_secs(5),
            max_search_dist: 400.0,
        };
        find_path(&b.grid(), &Costs::test_default(), &q)
    }

    #[test]
    fn flat_ground_stays_flat() {
        let b = flat(48);
        let r = run(&b, (4, 65, 4), (40, 65, 40));
        assert_eq!(r.outcome, Outcome::Found);
        assert!(r.path.iter().all(|p| p.1 == 65), "{:?}", r.path);
        assert_eq!(r.path.last(), Some(&(40, 65, 40)));
    }

    #[test]
    fn does_not_stop_next_to_destination() {
        let b = flat(16);
        let r = run(&b, (5, 65, 5), (6, 65, 5));
        assert_eq!(r.outcome, Outcome::Found);
        assert_eq!(r.path, vec![(5, 65, 5), (6, 65, 5)]);
    }

    #[test]
    fn refuses_damaging_five_block_drop() {
        let mut b = GridBuilder::new((0, 55, 0), (40, 20, 40));
        b.fill((0, 55, 0), (39, 59, 39), GridBuilder::STONE);
        b.fill((0, 60, 0), (19, 64, 39), GridBuilder::STONE);
        let r = run(&b, (10, 65, 20), (30, 60, 20));
        assert_ne!(r.outcome, Outcome::Found);
    }

    #[test]
    fn walks_around_a_block_pile_instead_of_over_it() {
        // The screenshot bug: a 3-high pile between start and goal on open ground.
        let mut b = flat(48);
        b.fill((20, 65, 16), (26, 67, 30), GridBuilder::STONE);
        let r = run(&b, (10, 65, 23), (36, 65, 23));
        assert_eq!(r.outcome, Outcome::Found);
        assert!(r.path.iter().all(|p| p.1 == 65), "climbed the pile: {:?}", r.path);
    }

    #[test]
    fn climbs_when_it_must() {
        let mut b = flat(40);
        // A full-width 1-block step.
        b.fill((20, 65, 0), (39, 65, 39), GridBuilder::STONE);
        let r = run(&b, (10, 65, 20), (30, 66, 20));
        assert_eq!(r.outcome, Outcome::Found);
        assert_eq!(r.path.last().unwrap().1, 66);
    }

    #[test]
    fn unknown_terrain_is_a_wall() {
        let mut b = flat(40);
        // Zeroed column band = unknown data: must not be walked through.
        b.fill((20, 60, 0), (21, 75, 39), 0);
        let r = run(&b, (10, 65, 20), (30, 65, 20));
        assert_ne!(r.outcome, Outcome::Found);
    }

    #[test]
    fn never_steps_inside_blocks() {
        let mut b = flat(40);
        b.fill((15, 65, 10), (15, 66, 30), GridBuilder::STONE); // 2-high wall with a gap
        b.set(15, 65, 20, GridBuilder::AIR);
        b.set(15, 66, 20, GridBuilder::AIR);
        let g = b.grid();
        let r = run(&b, (5, 65, 20), (30, 65, 20));
        assert_eq!(r.outcome, Outcome::Found);
        for &(x, y, z) in &r.path {
            assert!(g.walkable(x, y, z) && g.walkable(x, y + 1, z), "inside block at {:?}", (x, y, z));
        }
    }

    /// `cargo test --release -- --ignored --nocapture` for a timing sample.
    #[test]
    #[ignore]
    fn large_maze_timing() {
        let n = 400;
        let mut b = GridBuilder::new((0, 60, 0), (n, 24, n));
        b.fill((0, 60, 0), (n - 1, FLOOR_Y, n - 1), GridBuilder::STONE);
        // Deterministic scatter of pillars and piles.
        let mut seed: u32 = 12345;
        for _ in 0..6000 {
            seed = seed.wrapping_mul(1103515245).wrapping_add(12345);
            let x = (seed >> 8) as i32 % (n - 4) + 2;
            seed = seed.wrapping_mul(1103515245).wrapping_add(12345);
            let z = (seed >> 8) as i32 % (n - 4) + 2;
            let h = 1 + (seed % 3) as i32;
            b.fill((x, 65, z), (x + 1, 64 + h, z + 1), GridBuilder::STONE);
        }
        let t = std::time::Instant::now();
        let r = run(&b, (5, 65, 5), (390, 65, 380));
        println!("{:?} explored={} len={} in {:?}", r.outcome, r.nodes_explored, r.path.len(), t.elapsed());
    }

    #[test]
    fn half_block_steps_are_walked_not_avoided() {
        // A raised band (full blocks, surface 66) with bottom-slab steps on both sides,
        // spanning z 0..=34. Walking over it is ~4 half-block steps; the detour round
        // its end at z 35+ is ~20 extra blocks. Pricing slab->block like a jump made
        // the detour cheaper.
        let slab = crate::grid::STANDABLE | crate::grid::PARTIAL | (512 << crate::grid::HEIGHT_SHIFT);
        let mut b = flat(48);
        b.fill((19, 65, 0), (19, 65, 34), slab);
        b.fill((20, 65, 0), (23, 65, 34), GridBuilder::STONE);
        b.fill((24, 65, 0), (24, 65, 34), slab);
        let r = run(&b, (4, 65, 24), (44, 65, 24));
        assert_eq!(r.outcome, Outcome::Found);
        assert!(r.path.iter().any(|p| p.1 == 66), "went around: {:?}", r.path);
        assert!(r.path.iter().all(|p| (p.2 - 24).abs() <= 2), "wandered: {:?}", r.path);
    }

    /// Slab staircase (floor, slab, block, slab-on-block, ...) 5 wide between walls.
    fn slab_staircase() -> GridBuilder {
        let slab = crate::grid::STANDABLE | crate::grid::PARTIAL | (512 << crate::grid::HEIGHT_SHIFT);
        let mut b = GridBuilder::new((0, 60, 0), (48, 24, 48));
        b.fill((0, 60, 0), (47, 64, 47), GridBuilder::STONE);
        for k in 0..12 {
            let x = 10 + k;
            let base = 65 + k / 2; // block level of this step
            if base > 65 {
                b.fill((x, 65, 20), (x, base - 1, 24), GridBuilder::STONE);
            }
            b.fill((x, base, 20), (x, base, 24), if k % 2 == 0 { slab } else { GridBuilder::STONE });
        }
        b.fill((22, 65, 20), (47, 70, 24), GridBuilder::STONE); // landing, surface 71
        b.fill((10, 65, 19), (47, 75, 19), GridBuilder::STONE); // side walls
        b.fill((10, 65, 25), (47, 75, 25), GridBuilder::STONE);
        b
    }

    #[test]
    fn slab_staircase_goes_straight_up() {
        let b = slab_staircase();
        let r = run(&b, (4, 65, 22), (30, 71, 22));
        assert_eq!(r.outcome, Outcome::Found);
        let on_stairs: Vec<_> = r.path.iter().filter(|p| (10..22).contains(&p.0)).collect();
        assert!(on_stairs.iter().all(|p| p.2 == on_stairs[0].2), "side-stepped: {:?}", r.path);
    }

    #[test]
    fn slab_staircase_smooths_to_one_segment() {
        let b = slab_staircase();
        let g = b.grid();
        let r = run(&b, (4, 65, 22), (30, 71, 22));
        let s = crate::smooth::smooth(&g, &Costs::test_default(), &r.path);
        assert!(s.len() <= 3, "{} raw -> {s:?}", r.path.len());
    }
}
