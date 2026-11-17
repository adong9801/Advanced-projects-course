//! The functions Java calls (declared `native` in NativePathfinder.java).
//!
//! JNI is how Java calls into a native library. Instead of using the `jni`
//! crate, the few JNI functions needed are looked up by hand in the JNIEnv
//! function table, by their numbers from the JNI spec.
//!
//! - `nativeFindV5`: the search the mod uses (`v5`)
//! - `nativeFindPathV2`: the first version's search, still used by
//!   NativeBridgeTest

use crate::costs::{self, Costs};
use crate::grid::Grid;
use crate::search::{self, Outcome, Query};
use crate::v5::{self, Pos, Status};
use std::ffi::c_void;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::time::Duration;

type JObject = *mut c_void;
type JEnv = *const *const *const c_void;

const GET_ARRAY_LENGTH: usize = 171;
const NEW_INT_ARRAY: usize = 179;
const GET_INT_ARRAY_REGION: usize = 203;
const SET_INT_ARRAY_REGION: usize = 211;
const GET_DIRECT_BUFFER_ADDRESS: usize = 230;
const GET_DIRECT_BUFFER_CAPACITY: usize = 231;

unsafe fn func(env: JEnv, index: usize) -> *const c_void {
    *(*env).add(index)
}

/// Borrow a direct ByteBuffer's memory; `None` for heap buffers / null.
unsafe fn direct_bytes<'a>(env: JEnv, buf: JObject) -> Option<&'a [u8]> {
    if buf.is_null() {
        return None;
    }
    let addr: extern "system" fn(JEnv, JObject) -> *mut c_void =
        std::mem::transmute(func(env, GET_DIRECT_BUFFER_ADDRESS));
    let cap: extern "system" fn(JEnv, JObject) -> i64 =
        std::mem::transmute(func(env, GET_DIRECT_BUFFER_CAPACITY));
    let ptr = addr(env, buf);
    let len = cap(env, buf);
    if ptr.is_null() || len < 0 {
        return None;
    }
    Some(std::slice::from_raw_parts(ptr as *const u8, len as usize))
}

unsafe fn to_int_array(env: JEnv, data: &[i32]) -> JObject {
    let new_arr: extern "system" fn(JEnv, i32) -> JObject =
        std::mem::transmute(func(env, NEW_INT_ARRAY));
    let set_region: extern "system" fn(JEnv, JObject, i32, i32, *const i32) =
        std::mem::transmute(func(env, SET_INT_ARRAY_REGION));
    let arr = new_arr(env, data.len() as i32);
    if !arr.is_null() {
        set_region(env, arr, 0, data.len() as i32, data.as_ptr());
    }
    arr
}

unsafe fn from_int_array(env: JEnv, arr: JObject) -> Option<Vec<i32>> {
    if arr.is_null() {
        return None;
    }
    let len_fn: extern "system" fn(JEnv, JObject) -> i32 =
        std::mem::transmute(func(env, GET_ARRAY_LENGTH));
    let get_region: extern "system" fn(JEnv, JObject, i32, i32, *mut i32) =
        std::mem::transmute(func(env, GET_INT_ARRAY_REGION));
    let len = len_fn(env, arr);
    if len < 0 {
        return None;
    }
    let mut v = vec![0i32; len as usize];
    get_region(env, arr, 0, len, v.as_mut_ptr());
    Some(v)
}

unsafe fn grid_from<'a>(
    env: JEnv,
    blocks: JObject,
    min: (i32, i32, i32),
    size: (i32, i32, i32),
) -> Option<Grid<'a>> {
    let bytes = direct_bytes(env, blocks)?;
    if bytes.as_ptr() as usize % 2 != 0 || bytes.len() % 2 != 0 {
        return None;
    }
    let cells = std::slice::from_raw_parts(bytes.as_ptr() as *const u16, bytes.len() / 2);
    Grid::new(cells, min, size)
}

// ---- The first version's search ----

/// Status codes in slot 0 of the returned arrays (both searches use them).
const RESULT_NO_PATH: i32 = 0;
const RESULT_FOUND: i32 = 1;
const RESULT_NODE_LIMIT: i32 = 2;
const RESULT_TIMED_OUT: i32 = 3;
const RESULT_BAD_INPUT: i32 = -1;

/// Returns `int[] { result, nodesExplored, pathLength, x1, y1, z1, ... }`.
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub unsafe extern "system" fn Java_com_rustpath_client_pathfinder_NativePathfinder_nativeFindPathV2(
    env: JEnv,
    _class: JObject,
    blocks: JObject,
    min_x: i32,
    min_y: i32,
    min_z: i32,
    size_x: i32,
    size_y: i32,
    size_z: i32,
    costs_buf: JObject,
    start_x: i32,
    start_y: i32,
    start_z: i32,
    goal_x: i32,
    goal_y: i32,
    goal_z: i32,
    max_nodes: i32,
    max_time_ms: i64,
    max_search_dist: f64,
) -> JObject {
    let run = || -> Vec<i32> {
        let bad = vec![RESULT_BAD_INPUT, 0, 0];
        let Some(cost_bytes) = direct_bytes(env, costs_buf) else {
            return bad;
        };
        let Some(costs) =
            Costs::from_bytes(&cost_bytes[..cost_bytes.len().min(costs::SERIALIZED_LEN)])
        else {
            return bad;
        };
        let Some(grid) = grid_from(env, blocks, (min_x, min_y, min_z), (size_x, size_y, size_z))
        else {
            return bad;
        };
        let query = Query {
            start: (start_x, start_y, start_z),
            goal: (goal_x, goal_y, goal_z),
            max_nodes: max_nodes.max(1) as usize,
            max_time: Duration::from_millis(max_time_ms.max(1) as u64),
            max_search_dist,
        };
        let r = search::find_path(&grid, &costs, &query);
        let code = match r.outcome {
            Outcome::Found => RESULT_FOUND,
            Outcome::NoPath => RESULT_NO_PATH,
            Outcome::NodeLimit => RESULT_NODE_LIMIT,
            Outcome::TimedOut => RESULT_TIMED_OUT,
        };
        let mut out = Vec::with_capacity(3 + r.path.len() * 3);
        out.extend([code, r.nodes_explored as i32, r.path.len() as i32]);
        for (x, y, z) in r.path {
            out.extend([x, y, z]);
        }
        out
    };
    // A panic must never unwind into the JVM.
    let result =
        catch_unwind(AssertUnwindSafe(run)).unwrap_or_else(|_| vec![RESULT_BAD_INPUT, 0, 0]);
    to_int_array(env, &result)
}

// ---- The current search ----

// Where things are in the request array V5Search.java sends: a 16-int
// header, then the starts and goals (x, y, z each), then the avoid zones
// (6 ints each).
const VERSION: usize = 0; // 1 for now
const MIN_X: usize = 1; // the snapshot's lowest corner, x y z
const SIZE_X: usize = 4; // the snapshot's size, x y z
const WORLD_MIN_Y: usize = 7;
const WORLD_MAX_Y: usize = 8;
const MAX_ITERATIONS: usize = 9;
const SEED: usize = 10;
const MAX_CLIMB: usize = 11;
const TIMEOUT_MS: usize = 12;
const START_COUNT: usize = 13;
const GOAL_COUNT: usize = 14;
const AVOID_COUNT: usize = 15;
const HEADER_LEN: usize = 16;

/// Past this is outside Minecraft's world border, so it can only be bad input.
const MAX_COORD: i32 = 31_000_000;

fn valid_coord(v: i32) -> bool {
    (-MAX_COORD..=MAX_COORD).contains(&v)
}

struct V5Request<'a> {
    grid: v5::Grid<'a>,
    starts: Vec<Pos>,
    goals: Vec<Pos>,
    avoid: Vec<v5::Avoid>,
    max_iterations: usize,
    seed: i32,
    max_climb: i32,
    timeout: Duration,
}

/// Read and check everything Java sent. Anything wrong gives `None`, so a
/// bad request can't make Rust read past the end of a buffer.
unsafe fn read_v5_request<'a>(
    env: JEnv,
    cells: JObject,
    corners: JObject,
    request: JObject,
) -> Option<V5Request<'a>> {
    let r = from_int_array(env, request)?;
    if r.len() < HEADER_LEN || r[VERSION] != 1 {
        return None;
    }
    let min = [r[MIN_X], r[MIN_X + 1], r[MIN_X + 2]];
    let size = [r[SIZE_X], r[SIZE_X + 1], r[SIZE_X + 2]];
    if size.iter().any(|&s| s <= 0)
        || !min.iter().all(|&v| valid_coord(v))
        || r[WORLD_MIN_Y] >= r[WORLD_MAX_Y]
        || r[MAX_ITERATIONS] <= 0
        || r[MAX_ITERATIONS] > 5_000_000
        || r[MAX_CLIMB] < 1
        || r[MAX_CLIMB] > 32
        || r[TIMEOUT_MS] < 0
    {
        return None;
    }

    // The two buffers have to be exactly the snapshot's size
    let count = size.iter().try_fold(1usize, |total, &s| total.checked_mul(s as usize))?;
    if count > 16_000_000 {
        return None;
    }
    let cell_bytes = direct_bytes(env, cells)?;
    let corner_bytes = direct_bytes(env, corners)?;
    if cell_bytes.len() != count.checked_mul(2)?
        || cell_bytes.as_ptr() as usize % 2 != 0
        || corner_bytes.len() != count
    {
        return None;
    }

    let start_count = usize::try_from(r[START_COUNT]).ok()?;
    let goal_count = usize::try_from(r[GOAL_COUNT]).ok()?;
    let avoid_count = usize::try_from(r[AVOID_COUNT]).ok()?;
    if start_count == 0
        || goal_count == 0
        || start_count > 4096
        || goal_count > 4096
        || avoid_count > 4096
        || r.len() != HEADER_LEN + (start_count + goal_count) * 3 + avoid_count * 6
    {
        return None;
    }

    let positions = |ints: &[i32]| -> Option<Vec<Pos>> {
        ints.chunks_exact(3)
            .map(|p| {
                if p.iter().all(|&v| valid_coord(v)) {
                    Some([p[0], p[1], p[2]])
                } else {
                    None
                }
            })
            .collect()
    };
    let goals_at = HEADER_LEN + start_count * 3;
    let avoid_at = goals_at + goal_count * 3;
    let starts = positions(&r[HEADER_LEN..goals_at])?;
    let goals = positions(&r[goals_at..avoid_at])?;

    let mut avoid = vec![];
    for zone in r[avoid_at..].chunks_exact(6) {
        // The penalty is a float, sent as its bits
        let penalty = f32::from_bits(zone[5] as u32);
        if !penalty.is_finite() || penalty < 0. || zone[3] < 0 || zone[4] < 0 {
            return None;
        }
        avoid.push(v5::Avoid {
            pos: [zone[0], zone[1], zone[2]],
            radius_sq: zone[3],
            max_y: zone[4],
            penalty,
        });
    }

    Some(V5Request {
        grid: v5::Grid {
            cells: std::slice::from_raw_parts(cell_bytes.as_ptr() as *const u16, count),
            corners: corner_bytes,
            min,
            size,
            world_y: [r[WORLD_MIN_Y], r[WORLD_MAX_Y]],
        },
        starts,
        goals,
        avoid,
        max_iterations: r[MAX_ITERATIONS] as usize,
        seed: r[SEED],
        max_climb: r[MAX_CLIMB],
        timeout: Duration::from_millis(r[TIMEOUT_MS] as u64),
    })
}

/// Returns `int[] { status, explored, length, startIndex, signatureLow,
/// signatureHigh, then x, y, z, flags for each block of the route }`.
/// A bad request gives a status of -1.
#[no_mangle]
pub unsafe extern "system" fn Java_com_rustpath_client_pathfinder_NativePathfinder_nativeFindV5(
    env: JEnv,
    _class: JObject,
    cells: JObject,
    corners: JObject,
    request: JObject,
) -> JObject {
    let run = || -> Option<Vec<i32>> {
        let request = read_v5_request(env, cells, corners, request)?;
        let query = v5::Query {
            starts: &request.starts,
            goals: &request.goals,
            avoid: &request.avoid,
            max_iterations: request.max_iterations,
            seed: request.seed,
            max_climb: request.max_climb,
            timeout: request.timeout,
        };
        let found = v5::find(&request.grid, &query);
        let status = match found.status {
            Status::Found => RESULT_FOUND,
            Status::NoPath => RESULT_NO_PATH,
            Status::NodeLimit => RESULT_NODE_LIMIT,
            Status::TimedOut => RESULT_TIMED_OUT,
        };
        let mut out = vec![
            status,
            found.explored as i32,
            found.path.len() as i32,
            found.start_index as i32,
            found.signature as i32,
            (found.signature >> 32) as i32,
        ];
        for (p, flags) in found.path.iter().zip(&found.flags) {
            out.extend([p[0], p[1], p[2], *flags]);
        }
        Some(out)
    };
    // A panic must never unwind into the JVM, so it's sent back as bad input
    let out = catch_unwind(AssertUnwindSafe(run))
        .ok()
        .flatten()
        .unwrap_or_else(|| vec![RESULT_BAD_INPUT, 0, 0, 0, 0, 0]);
    to_int_array(env, &out)
}
