//! The moves the player can make from a block, and what each one costs.
//!
//! Costs are roughly in ticks (a tick is 1/20 of a second), plus extra for
//! things that are hard for the walker: running along an edge, hugging a
//! wall, big drops. The numbers come from V5, which my Java pathfinder mod's
//! search is based on, so the routes match the Java version's.

use super::world::*;
use std::collections::HashMap;

/// Ticks to sprint one block.
pub const SPRINT: f32 = 1.0 / 0.2806;
pub const DIAGONAL: f32 = SPRINT * 1.41421356;
/// Jumping up a full block.
pub const JUMP: f32 = 28.0 + 6.0 + SPRINT;
/// Walking up a slab or a stair, no jump needed.
const SLAB_STEP: f32 = SPRINT * 1.1;

/// Extra cost for how close the nearest edge or wall is: index 0 means right
/// next to it, 6 means nothing within 6 blocks.
const EDGE_COST: [f32; 7] = [24., 19.5, 16., 11.5, 5.5, 3.7, 0.5];
const WALL_COST: [f32; 7] = [17., 13.5, 11., 6.5, 3., 1.5, 0.2];

/// The 8 directions around a block: 4 straight, then 4 diagonal.
pub const DIRECTIONS: [(i32, i32); 8] = [
    (0, -1),
    (0, 1),
    (1, 0),
    (-1, 0),
    (1, -1),
    (-1, -1),
    (1, 1),
    (-1, 1),
];

#[derive(Clone, Copy)]
pub enum Move {
    /// Walk to the next block on the same level (straight or diagonal).
    Walk(i32, i32),
    /// Jump up onto the next block.
    JumpUp(i32, i32),
    /// Walk off an edge and fall to the floor below.
    Drop(i32, i32),
}

use Move::*;

/// Every move the search tries, in this order. The order matters: it decides
/// which of two equally good routes wins, and it has to be the same as Java's.
#[rustfmt::skip]
pub const MOVES: [Move; 16] = [
    Walk(0, -1), Walk(0, 1), Walk(1, 0), Walk(-1, 0),
    Walk(1, -1), Walk(-1, -1), Walk(1, 1), Walk(-1, 1),
    JumpUp(0, -1), JumpUp(0, 1), JumpUp(1, 0), JumpUp(-1, 0),
    Drop(0, -1), Drop(0, 1), Drop(1, 0), Drop(-1, 0),
];

/// The checks that get cached per block, because the same block is looked at
/// from lots of neighbours.
#[derive(Clone, Copy, PartialEq, Eq, Hash)]
enum Check {
    Standable,
    /// Nothing solid here or in the two blocks under it: you'd fall down a hole.
    Hole,
    /// You'd walk into something at this block.
    Wall,
}

pub struct Terrain<'a> {
    grid: &'a Grid<'a>,
    checked: HashMap<(Pos, Check), bool>,
    penalties: HashMap<Pos, f32>,
    /// `fall_ticks[n]` is how many ticks falling n blocks takes (plus 2).
    pub fall_ticks: [f32; 257],
}

impl<'a> Terrain<'a> {
    pub fn new(grid: &'a Grid<'a>) -> Self {
        // Simulate Minecraft's gravity tick by tick: 0.08 down, then 2% drag
        let mut fall_ticks = [0.; 257];
        let (mut fallen, mut velocity, mut ticks) = (0.0f32, 0.0f32, 0);
        for (blocks, entry) in fall_ticks.iter_mut().enumerate().skip(1) {
            while fallen < blocks as f32 {
                velocity = (velocity - 0.08) * 0.98;
                fallen -= velocity;
                ticks += 1;
            }
            *entry = ticks as f32 + 2.;
        }
        Terrain {
            grid,
            checked: HashMap::new(),
            penalties: HashMap::new(),
            fall_ticks,
        }
    }

    fn flags(&self, p: Pos) -> u16 {
        self.grid.flags(p)
    }

    fn open(&self, p: Pos) -> bool {
        passable(self.flags(p))
    }

    /// Solid ground you can stand on (fences don't count).
    fn is_floor(&self, p: Pos) -> bool {
        has(self.flags(p), SOLID) && !has(self.flags(p), FENCE)
    }

    fn standable_dry(&self, p: Pos) -> bool {
        self.grid.standable(p) && !has(self.flags(p), FLUID)
    }

    fn check(&mut self, p: Pos, check: Check) -> bool {
        if let Some(&answer) = self.checked.get(&(p, check)) {
            return answer;
        }
        let answer = match check {
            Check::Standable => self.grid.standable(p),
            Check::Hole => {
                !has(self.flags(p), SOLID)
                    && !has(self.flags(below(p, 1)), SOLID)
                    && !has(self.flags(below(p, 2)), SOLID)
            }
            Check::Wall => {
                let feet = self.flags(p);
                let head = self.flags(above(p, 1));
                if has(head, WALL) {
                    true
                } else if has(head, SOLID) {
                    // Something low but solid at head height (a slab, stair
                    // or snow). It's a wall, unless this block is a slab or
                    // stair too, like on a staircase.
                    has(head, SLAB_TOP) || (!has(feet, SLAB_BOTTOM) && !has(feet, STAIRS))
                } else {
                    has(feet, WALL)
                }
            }
        };
        self.checked.insert((p, check), answer);
        answer
    }

    /// Is the block next to `p` in this direction a slab or stair step you'd
    /// walk up or down? Those don't count as edges or walls.
    fn is_step(&self, p: Pos, dx: i32, dz: i32) -> bool {
        let next = offset(p, dx, 0, dz);
        let level = self.flags(next);
        let step_up = has(level, STAIRS | SLAB_BOTTOM)
            && has(level, SOLID)
            && self.open(above(next, 1))
            && self.open(above(next, 2));
        if step_up {
            return true;
        }
        let lower = self.flags(below(next, 2));
        has(lower, STAIRS | SLAB_BOTTOM)
            && has(lower, SOLID)
            && self.open(below(next, 1))
            && passable(level)
    }

    /// Extra cost for standing at `p`: how close the nearest edge and wall
    /// are, and whether you'd be in water. This is what keeps routes away
    /// from ledges and walls.
    fn penalty(&mut self, p: Pos) -> f32 {
        if let Some(&cost) = self.penalties.get(&p) {
            return cost;
        }

        // Only look in directions that aren't stairs or slabs (if every
        // direction is a step, look everywhere)
        let mut directions: Vec<(i32, i32)> = DIRECTIONS
            .into_iter()
            .filter(|&(dx, dz)| !self.is_step(p, dx, dz))
            .collect();
        if directions.is_empty() {
            directions = DIRECTIONS.to_vec();
        }

        let mut nearest_edge = 6;
        let mut nearest_wall = 6;
        for (dx, dz) in directions {
            let mut edge = 6;
            let mut wall = 6;
            for distance in 1..=6 {
                if edge != 6 && wall != 6 {
                    break;
                }
                let block = offset(p, dx * distance, 0, dz * distance);
                if edge == 6 && self.check(block, Check::Hole) {
                    edge = (distance - 1) as usize;
                }
                if wall == 6 && self.check(block, Check::Wall) {
                    wall = (distance - 1) as usize;
                }
            }
            nearest_edge = nearest_edge.min(edge);
            nearest_wall = nearest_wall.min(wall);
            if nearest_edge == 0 && nearest_wall == 0 {
                break;
            }
        }

        let mut cost = EDGE_COST[nearest_edge] + WALL_COST[nearest_wall];
        if has(self.flags(p), FLUID) {
            cost += 20.;
        }
        if has(self.flags(above(p, 1)), FLUID) {
            cost += 20.;
        }
        self.penalties.insert(p, cost);
        cost
    }

    /// Is `p` at the bottom of a pit? (walls 2 high on at least 3 sides,
    /// within 3 blocks) Dropping into one is avoided, it's hard to get out.
    fn in_pit(&self, p: Pos) -> bool {
        let mut walls = 0;
        for (dx, dz) in [(1, 0), (-1, 0), (0, 1), (0, -1)] {
            for distance in 1..=3 {
                let block = offset(p, dx * distance, 0, dz * distance);
                if !self.open(block) {
                    if !self.open(above(block, 1)) {
                        walls += 1;
                    }
                    break;
                }
                if !self.is_floor(below(block, 1)) {
                    break;
                }
            }
        }
        walls >= 3
    }

    /// Where a move from `p` ends up and what it costs, or `None` if the
    /// player can't make it.
    pub fn try_move(&mut self, p: Pos, m: Move) -> Option<(Pos, f32)> {
        match m {
            Walk(dx, dz) => self.walk(p, dx, dz),
            JumpUp(dx, dz) => self.jump_up(p, dx, dz),
            Drop(dx, dz) => self.drop_down(p, dx, dz),
        }
    }

    fn walk(&mut self, p: Pos, dx: i32, dz: i32) -> Option<(Pos, f32)> {
        let to = offset(p, dx, 0, dz);
        if !self.check(to, Check::Standable) {
            return None;
        }
        let mut cost = SPRINT;
        if dx != 0 && dz != 0 {
            // Going diagonally, both blocks you pass between have to be open
            // too, or you'd clip the corner
            let side_a = offset(p, dx, 0, 0);
            let side_b = offset(p, 0, 0, dz);
            let corner_open = self.open(side_a)
                && self.open(side_b)
                && self.open(above(side_a, 1))
                && self.open(above(side_b, 1));
            if !corner_open && !self.grid.squeezes(p, dx, dz) {
                return None;
            }
            cost = DIAGONAL + if corner_open { 0. } else { 3. };
        }
        Some((to, cost + self.penalty(to)))
    }

    fn jump_up(&mut self, p: Pos, dx: i32, dz: i32) -> Option<(Pos, f32)> {
        // `step` is the block you jump onto, you end up standing on top of it
        let step = offset(p, dx, 0, dz);
        // Room above your head to jump, and room to stand on top of the step
        if !self.open(above(p, 2))
            || !self.is_floor(step)
            || !self.open(above(step, 1))
            || !self.open(above(step, 2))
        {
            return None;
        }
        let standing_on = self.flags(below(p, 1));
        let onto = self.flags(step);
        // From a bottom slab you can only step up onto another bottom slab
        if has(standing_on, SLAB_BOTTOM) && !has(onto, SLAB_BOTTOM) {
            return None;
        }
        let walked_up = has(onto, SLAB_BOTTOM) || has(standing_on, STAIRS) || has(onto, STAIRS);
        let mut cost = if walked_up { SLAB_STEP } else { JUMP };
        if !walked_up && (!self.open(above(step, 3)) || !self.open(above(p, 3))) {
            // A real jump with a low ceiling: you'd bump your head
            cost += 25.;
        }
        let to = above(step, 1);
        Some((to, cost + self.penalty(to)))
    }

    fn drop_down(&mut self, p: Pos, dx: i32, dz: i32) -> Option<(Pos, f32)> {
        let edge = offset(p, dx, 0, dz);
        // Room to walk off: head, feet and the gap below have to be open
        if !self.open(above(edge, 1)) || !self.open(edge) || !self.open(below(edge, 1)) {
            return None;
        }
        // Fall until there's something under you, up to 20 blocks
        for fall in 1..=20 {
            let floor = below(edge, fall + 1);
            if self.open(floor) {
                continue;
            }
            if !self.is_floor(floor) {
                return None;
            }
            let to = above(floor, 1);
            let mut cost = SPRINT * 0.5 + self.fall_ticks[fall as usize];
            if fall > 3 {
                cost += ((fall - 3) * (fall - 3)) as f32 * 2.;
            }
            if fall >= 2 && self.in_pit(to) {
                cost += 40.;
            }
            if fall >= 2 {
                // The Java mod's rule: avoid dropping when there's a way round
                cost += 60. + 10. * (fall - 2) as f32;
            }
            cost += self.penalty(to);
            return Some((to, cost));
        }
        None
    }

    /// Climbing more than one block in one go (only searched when the
    /// player can jump higher than normal).
    pub fn climb(&mut self, p: Pos, dx: i32, dz: i32, height: i32) -> Option<(Pos, f32)> {
        for k in 2..=height + 1 {
            if !self.open(above(p, k)) {
                return None;
            }
        }
        let to = offset(p, dx, height, dz);
        if !self.is_floor(below(to, 1))
            || !self.open(to)
            || !self.open(above(to, 1))
            || !self.open(above(to, 2))
        {
            return None;
        }
        let ladder = has(self.flags(p), CLIMBABLE)
            || has(self.flags(above(p, 1)), CLIMBABLE)
            || has(self.flags(below(to, 1)), CLIMBABLE);
        // A run-up (two blocks you can stand on behind you) makes it easier
        let run_up = !ladder
            && self.standable_dry(offset(p, -dx, 0, -dz))
            && self.standable_dry(offset(p, -2 * dx, 0, -2 * dz));
        let no_run_up = if run_up { 0. } else { 20. };
        let ladder_cost = if ladder { 20. } else { 0. };
        let cost = JUMP + no_run_up + ladder_cost + 2. * height as f32;
        Some((to, cost + self.penalty(to)))
    }
}
