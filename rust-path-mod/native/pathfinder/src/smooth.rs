//! Path smoothing: greedy string-pulling with a swept-hitbox walkability test.
//!
//! Replaces `AStarPathfinder.smoothPath`, which traced only the centre line
//! (so pulled paths clipped wall corners) and ran on the render thread. Here
//! a shortcut is accepted only if the player's footprint, inflated by a small
//! wall margin, can walk the whole segment using the same step-up / walk-off
//! limits as the planner, without passing near a drop the planner avoided.

use crate::costs::Costs;
use crate::grid::Grid;

pub type P = (i32, i32, i32);

/// Player half-width (0.3) plus clearance so pulled paths keep off walls.
const RADIUS: f64 = 0.4;
const SAMPLE_STEP: f64 = 0.2;
/// How many raw nodes ahead a single shortcut may reach.
const MAX_LOOKAHEAD: usize = 128;
/// Reject shortcuts whose raw route is this much longer than the chord: the
/// route went around something (a spiral stair, a pit) for a reason.
const DETOUR_RATIO: f64 = 2.5;
const PLAYER_HEIGHT: f64 = 1.8;

pub fn smooth(grid: &Grid, _costs: &Costs, path: &[P]) -> Vec<P> {
    if path.len() <= 2 {
        return path.to_vec();
    }
    let mut cum = vec![0.0; path.len()];
    for i in 1..path.len() {
        cum[i] = cum[i - 1] + dist3(path[i - 1], path[i]);
    }

    let mut out = vec![path[0]];
    let mut anchor = 0;
    while anchor < path.len() - 1 {
        let last = (anchor + MAX_LOOKAHEAD).min(path.len() - 1);
        let mut next = anchor + 1;
        for j in (anchor + 2..=last).rev() {
            let chord = dist3(path[anchor], path[j]);
            if cum[j] - cum[anchor] > chord * DETOUR_RATIO + 1.0 {
                continue;
            }
            if walkable_segment(grid, path[anchor], path[j]) {
                next = j;
                break;
            }
        }
        out.push(path[next]);
        anchor = next;
    }
    out
}

fn dist3(a: P, b: P) -> f64 {
    let (dx, dy, dz) = ((b.0 - a.0) as f64, (b.1 - a.1) as f64, (b.2 - a.2) as f64);
    (dx * dx + dy * dy + dz * dz).sqrt()
}

/// Feet level and surface height the player would have standing in a cell.
#[derive(Clone, Copy)]
struct Stand {
    y: i32,
    surface: f64,
}

/// Can the player stand in column (x, z) at feet level `y`?
fn stand_at(grid: &Grid, x: i32, y: i32, z: i32) -> Option<Stand> {
    if !grid.has_ground_support(x, y, z) || grid.is_ice_ground(x, y, z) {
        return None;
    }
    let surface = grid.surface_height(x, y, z);
    body_fits(grid, x, z, y, surface).then_some(Stand { y, surface })
}

/// Every block the body overlaps at this surface height is passable; the
/// block at feet level may be solid only if its top is under the feet.
fn body_fits(grid: &Grid, x: i32, z: i32, feet_y: i32, surface: f64) -> bool {
    let top = (surface + PLAYER_HEIGHT).floor() as i32;
    (feet_y..=top).all(|cy| {
        grid.walkable(x, cy, z) || (cy == feet_y && grid.block_height(x, cy, z) <= surface + 1e-6)
    })
}

use crate::moves::STEP_HEIGHT;

/// Can a *part* of the hitbox occupy column (x, z) while the player stands at
/// `surface` elsewhere? On stairs and slabs the footprint overhangs the next
/// (or previous) step, so solid blocks at or just above feet level are fine
/// if they are a step the player walks up (auto-step height, or a stair/slab
/// rise, which the grid stores at half height) and there's head room above.
pub(crate) fn footprint_fits(grid: &Grid, x: i32, z: i32, feet_y: i32, surface: f64) -> bool {
    let mut floor = surface;
    let mut clear_from = feet_y;
    for cy in feet_y..=feet_y + 1 {
        if grid.walkable(x, cy, z) {
            continue;
        }
        let top = grid.block_height(x, cy, z);
        let limit = surface + if grid.stair(x, cy, z) { 1.0 } else { STEP_HEIGHT };
        if top > limit + 1e-6 {
            return false;
        }
        floor = floor.max(top);
        clear_from = cy + 1;
    }
    let head = (floor + PLAYER_HEIGHT).floor() as i32;
    (clear_from..=head).all(|cy| grid.walkable(x, cy, z))
}

/// Ground within about a block under this column. The inflated footprint
/// must be over floor everywhere, so a shortcut can run along an edge (keeping
/// the centre `RADIUS` from it) but never hang over the drop. This replaces
/// the planner's 1-3 block edge-distance rule, which rejected every shortcut
/// on walkways the planner itself had routed along via step moves.
fn has_floor(grid: &Grid, x: i32, z: i32, feet_y: i32) -> bool {
    grid.has_ground_support(x, feet_y, z) || grid.has_ground_support(x, feet_y - 1, z)
}

/// Walk from `a` to `b` in small steps, following the ground.
fn walkable_segment(grid: &Grid, a: P, b: P) -> bool {
    explain_segment(grid, a, b).is_ok()
}

/// `walkable_segment`, with the reason a shortcut is refused (for `examples/diagnose.rs`).
pub fn explain_segment(grid: &Grid, a: P, b: P) -> Result<(), String> {
    let (dx, dz) = ((b.0 - a.0) as f64, (b.2 - a.2) as f64);
    let horizontal = (dx * dx + dz * dz).sqrt();
    if horizontal < 1e-9 {
        return if (b.1 - a.1).abs() <= 1 { Ok(()) } else { Err("vertical".into()) };
    }
    if (b.1 - a.1).abs() as f64 > horizontal + 1.0 {
        return Err("steeper than stairs".into());
    }

    let Some(mut cur) = stand_at(grid, a.0, a.1, a.2) else { return Err(format!("can't stand at start {a:?}")) };
    let (mut cx, mut cz) = (a.0, a.2);
    let steps = (horizontal / SAMPLE_STEP).ceil() as usize;
    let rising = b.1 > a.1;

    for k in 1..=steps {
        let t = k as f64 / steps as f64;
        let px = a.0 as f64 + 0.5 + dx * t;
        let pz = a.2 as f64 + 0.5 + dz * t;
        let (nx, nz) = (px.floor() as i32, pz.floor() as i32);

        if (nx, nz) != (cx, cz) {
            // Entering a new column: find the ground within one block, trying
            // the direction of travel first.
            let order = if rising { [0, 1, -1] } else { [0, -1, 1] };
            let found = order.iter().find_map(|&oy| {
                let s = stand_at(grid, nx, cur.y + oy, nz)?;
                let rise = s.surface - cur.surface;
                let step_ok = if rise > STEP_HEIGHT {
                    // No jumps inside a shortcut; only stair->stair (a real 0.5 rise stored as 1.0).
                    rise <= 1.0 && (grid.stair(nx, s.y - 1, nz) || grid.stair(nx, s.y, nz))
                } else {
                    rise >= -1.125
                };
                step_ok.then_some(s)
            });
            let Some(s) = found else {
                return Err(format!("no step into column ({nx},{nz}) from feet {} surface {:.2}", cur.y, cur.surface));
            };
            // Diagonal cell change: both corner columns must be open too.
            if nx != cx && nz != cz {
                let feet = cur.y.max(s.y);
                let surf = cur.surface.max(s.surface);
                if !footprint_fits(grid, cx, nz, feet, surf) || !footprint_fits(grid, nx, cz, feet, surf) {
                    return Err(format!("diagonal corner at ({cx},{nz})/({nx},{cz}), feet {feet} surface {surf:.2}"));
                }
            }
            cur = s;
            cx = nx;
            cz = nz;
        }

        // Swept footprint: every column the inflated hitbox touches.
        let (x0, x1) = ((px - RADIUS).floor() as i32, (px + RADIUS).floor() as i32);
        let (z0, z1) = ((pz - RADIUS).floor() as i32, (pz + RADIUS).floor() as i32);
        for fx in x0..=x1 {
            for fz in z0..=z1 {
                if (fx, fz) == (cx, cz) {
                    continue;
                }
                if !footprint_fits(grid, fx, fz, cur.y, cur.surface) {
                    return Err(format!("hitbox blocked in ({fx},{fz}) at feet {} surface {:.2}", cur.y, cur.surface));
                }
                if !has_floor(grid, fx, fz, cur.y) {
                    return Err(format!("hitbox over a drop in ({fx},{fz}) at feet {}", cur.y));
                }
            }
        }
    }
    // Must actually arrive at the target node's level.
    if (cx, cz) == (b.0, b.2) && (cur.y - b.1).abs() <= 1 {
        Ok(())
    } else {
        Err(format!("ended at ({cx},{},{cz}) not {b:?}", cur.y))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::grid::GridBuilder;

    fn floor(size: i32) -> GridBuilder {
        let mut b = GridBuilder::new((0, 60, 0), (size, 12, size));
        b.fill((0, 60, 0), (size - 1, 64, size - 1), GridBuilder::STONE);
        b
    }

    fn line(from: P, to: P) -> Vec<P> {
        // An L-shaped raw path: along x, then along z.
        let mut p = Vec::new();
        for x in from.0..=to.0 {
            p.push((x, from.1, from.2));
        }
        for z in from.2 + 1..=to.2 {
            p.push((to.0, from.1, z));
        }
        p
    }

    #[test]
    fn open_ground_collapses_to_a_line() {
        let b = floor(40);
        let raw = line((5, 65, 5), (30, 65, 30));
        let s = smooth(&b.grid(), &Costs::test_default(), &raw);
        assert_eq!(s, vec![(5, 65, 5), (30, 65, 30)]);
    }

    #[test]
    fn keeps_the_corner_around_a_wall() {
        let mut b = floor(40);
        // Wall filling the inside of the L so the diagonal is blocked.
        b.fill((10, 65, 8), (27, 67, 30), GridBuilder::STONE);
        let raw = line((5, 65, 5), (30, 65, 32));
        let g = b.grid();
        let costs = Costs::test_default();
        let s = smooth(&g, &costs, &raw);
        assert!(s.len() >= 3, "{s:?}");
        for w in s.windows(2) {
            assert!(walkable_segment(&g, w[0], w[1]), "{:?}", w);
        }
    }

    #[test]
    fn does_not_shortcut_a_jump() {
        let mut b = floor(40);
        b.fill((20, 65, 0), (39, 65, 39), GridBuilder::STONE); // full-block step up
        let mut raw: Vec<P> = (5..20).map(|x| (x, 65, 10)).collect();
        raw.extend((20..35).map(|x| (x, 66, 10)));
        let s = smooth(&b.grid(), &Costs::test_default(), &raw);
        assert!(s.contains(&(19, 65, 10)) && s.contains(&(20, 66, 10)), "{s:?}");
    }

    const STAIR: u16 =
        crate::grid::STANDABLE | crate::grid::PARTIAL | crate::grid::STAIR | (512 << crate::grid::HEIGHT_SHIFT);
    const SLAB: u16 = crate::grid::STANDABLE | crate::grid::PARTIAL | (512 << crate::grid::HEIGHT_SHIFT);

    /// Floor at 64, a 5-step staircase along +x (x=10..14, full-width), platform on top (surface 70).
    fn staircase() -> GridBuilder {
        let mut b = GridBuilder::new((0, 60, 0), (40, 16, 40));
        b.fill((0, 60, 0), (39, 64, 39), GridBuilder::STONE);
        for k in 0..5 {
            let x = 10 + k;
            if k > 0 {
                b.fill((x, 65, 0), (x, 64 + k, 39), GridBuilder::STONE);
            }
            b.fill((x, 65 + k, 0), (x, 65 + k, 39), STAIR);
        }
        b.fill((15, 65, 0), (39, 69, 39), GridBuilder::STONE);
        b
    }

    fn stair_raw() -> Vec<P> {
        let mut raw: Vec<P> = (3..10).map(|x| (x, 65, 20)).collect();
        raw.extend((0..5).map(|k| (10 + k, 65 + k, 20)));
        raw.extend((15..30).map(|x| (x, 70, 20)));
        raw
    }

    #[test]
    fn stairs_up_are_one_segment() {
        let b = staircase();
        let s = smooth(&b.grid(), &Costs::test_default(), &stair_raw());
        assert_eq!(s, vec![(3, 65, 20), (29, 70, 20)]);
    }

    #[test]
    fn stairs_down_are_one_segment() {
        let b = staircase();
        let mut raw = stair_raw();
        raw.reverse();
        let s = smooth(&b.grid(), &Costs::test_default(), &raw);
        assert_eq!(s, vec![(29, 70, 20), (3, 65, 20)]);
    }

    #[test]
    fn slab_steps_are_one_segment() {
        // Floor -> bottom slab -> full block -> slab -> block: half-block rises only.
        let slab = SLAB;
        let mut b = GridBuilder::new((0, 60, 0), (40, 16, 40));
        b.fill((0, 60, 0), (39, 64, 39), GridBuilder::STONE);
        b.fill((10, 65, 0), (10, 65, 39), slab);
        b.fill((11, 65, 0), (39, 65, 39), GridBuilder::STONE);
        b.fill((12, 66, 0), (12, 66, 39), slab);
        b.fill((13, 66, 0), (39, 66, 39), GridBuilder::STONE);
        let mut raw: Vec<P> = (3..10).map(|x| (x, 65, 20)).collect();
        raw.extend([(10, 65, 20), (11, 66, 20), (12, 66, 20)]);
        raw.extend((13..30).map(|x| (x, 67, 20)));
        let s = smooth(&b.grid(), &Costs::test_default(), &raw);
        assert_eq!(s, vec![(3, 65, 20), (29, 67, 20)]);
    }

    /// A 3-wide boardwalk at the edge of a 14-block cliff, alternating bottom
    /// slabs and full blocks along its length (the Galatea screenshot).
    fn cliff_boardwalk() -> GridBuilder {
        let mut b = GridBuilder::new((0, 40, 0), (40, 30, 40));
        b.fill((0, 40, 0), (39, 50, 39), GridBuilder::STONE); // valley floor, surface 51
        b.fill((0, 51, 20), (39, 63, 22), GridBuilder::STONE); // walkway pillar, top at 63
        for x in 0..40 {
            let block = if (x / 3) % 2 == 0 { SLAB } else { GridBuilder::STONE };
            b.fill((x, 64, 20), (x, 64, 22), block); // slab (surface 64.5) or block (surface 65)
        }
        b
    }

    fn boardwalk_node(g: &Grid, x: i32) -> P {
        // Planner convention: on a bottom slab the node is at the slab's Y, on a full block one above.
        if g.partial(x, 64, 21) { (x, 64, 21) } else { (x, 65, 21) }
    }

    #[test]
    fn boardwalk_along_a_cliff_is_one_segment() {
        let b = cliff_boardwalk();
        let g = b.grid();
        let raw: Vec<P> = (3..36).map(|x| boardwalk_node(&g, x)).collect();
        let s = smooth(&g, &Costs::test_default(), &raw);
        assert_eq!(s, vec![raw[0], *raw.last().unwrap()]);
    }

    #[test]
    fn never_pulls_the_path_over_the_drop() {
        // Hole in the walkway at x=15..17, z=21..22: only the z=20 strip continues.
        let mut b = cliff_boardwalk();
        b.fill((15, 51, 21), (17, 64, 22), GridBuilder::AIR);
        let g = b.grid();
        let mut raw: Vec<P> = (3..15).map(|x| boardwalk_node(&g, x)).collect();
        raw.extend((15..18).map(|x| {
            let (_, y, _) = boardwalk_node(&g, x);
            (x, y, 20)
        }));
        raw.extend((18..30).map(|x| boardwalk_node(&g, x)));
        let costs = Costs::test_default();
        let s = smooth(&g, &costs, &raw);
        assert!(s.len() < raw.len() / 2, "barely smoothed: {s:?}");
        for w in s.windows(2) {
            // Sample the chord: every point must be over the walkway, not the hole.
            let (a, c) = (w[0], w[1]);
            for k in 0..=20 {
                let t = k as f64 / 20.0;
                let x = (a.0 as f64 + 0.5 + (c.0 - a.0) as f64 * t).floor() as i32;
                let z = (a.2 as f64 + 0.5 + (c.2 - a.2) as f64 * t).floor() as i32;
                assert!(!((15..=17).contains(&x) && z >= 21), "{w:?} crosses the hole");
            }
        }
    }

    #[test]
    fn slab_to_higher_slab_is_a_jump() {
        // Bottom slab at 65 then bottom slab at 66 (on a block): a real 1.0 rise.
        let mut b = floor(40);
        b.fill((10, 65, 0), (19, 65, 39), SLAB);
        b.fill((20, 65, 0), (39, 65, 39), GridBuilder::STONE);
        b.fill((20, 66, 0), (39, 66, 39), SLAB);
        let mut raw: Vec<P> = (10..20).map(|x| (x, 65, 20)).collect();
        raw.extend((20..30).map(|x| (x, 66, 20)));
        let s = smooth(&b.grid(), &Costs::test_default(), &raw);
        assert!(s.contains(&(19, 65, 20)) && s.contains(&(20, 66, 20)), "{s:?}");
    }
}
