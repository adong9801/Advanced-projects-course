//! Movement costs, serialized by `BlockGridSerializer.serializeCosts`.
//! Layout (native endian): 6 f64, 257 f64 fall costs, 1 i32 max jump height.

pub const INF_COST: f64 = 1e6;
/// Must match `ActionCosts.JUMP_PENALTY` in Java.
pub const JUMP_PENALTY: f64 = 3.0;

pub const SERIALIZED_LEN: usize = 6 * 8 + 257 * 8 + 4;

#[derive(Clone)]
pub struct Costs {
    pub sprint: f64,
    pub walk: f64,
    pub sneak: f64,
    pub jump_one_block: f64,
    pub walk_off_one_block: f64,
    pub center_after_fall: f64,
    pub fall: [f64; 257],
    pub max_jump_height: i32,
}

impl Costs {
    pub fn from_bytes(buf: &[u8]) -> Option<Costs> {
        if buf.len() < SERIALIZED_LEN {
            return None;
        }
        let f = |i: usize| f64::from_ne_bytes(buf[i * 8..i * 8 + 8].try_into().unwrap());
        let mut fall = [0.0; 257];
        for (i, slot) in fall.iter_mut().enumerate() {
            *slot = f(6 + i);
        }
        let jump_off = 6 * 8 + 257 * 8;
        let costs = Costs {
            sprint: f(0),
            walk: f(1),
            sneak: f(2),
            jump_one_block: f(3),
            walk_off_one_block: f(4),
            center_after_fall: f(5),
            fall,
            max_jump_height: i32::from_ne_bytes(buf[jump_off..jump_off + 4].try_into().unwrap()),
        };
        let sane = [costs.sprint, costs.walk, costs.jump_one_block, costs.walk_off_one_block]
            .iter()
            .all(|c| c.is_finite() && *c > 0.0);
        sane.then_some(costs)
    }

    /// Default Java `ActionCosts(4)` values, for tests.
    #[cfg(test)]
    pub fn test_default() -> Costs {
        let walk = 1.0 / (0.1 * (0.16277136 / (0.91f64 * 0.91 * 0.91)) * 10.0);
        let sprint = 1.0 / (0.13 * (0.16277136 / (0.91f64 * 0.91 * 0.91)) * 10.0);
        let mut fall = [0.0; 257];
        for (i, slot) in fall.iter_mut().enumerate() {
            *slot = 2.0 + i as f64 * 1.5;
        }
        Costs {
            sprint,
            walk,
            sneak: walk * 3.0,
            jump_one_block: 20.0,
            walk_off_one_block: walk * 0.8,
            center_after_fall: walk * 0.2,
            fall,
            max_jump_height: 5,
        }
    }
}
