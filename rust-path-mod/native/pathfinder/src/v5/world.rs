//! How the search sees the world: one `u16` of flags per block, copied out of
//! Minecraft by `V5World.java`. The bit values have to match `V5Flags.java`.

pub type Pos = [i32; 3];

pub const PASSABLE: u16 = 1; // the player's body fits in it (air, grass, torches...)
pub const SOLID: u16 = 2; // something you can stand on top of
pub const WALL: u16 = 8; // blocks you when you walk into it sideways
pub const FLUID: u16 = 16;
pub const SLAB_BOTTOM: u16 = 32;
pub const SLAB_TOP: u16 = 64;
pub const FENCE: u16 = 128; // fences, walls, panes: solid, but you can't stand on them
pub const STAIRS: u16 = 256; // bottom-half stairs
pub const CARPET: u16 = 512;
pub const CLIMBABLE: u16 = 1024; // ladders and vines

pub const AIR: u16 = 5;
/// Anything not loaded counts as a solid wall, so routes never go into it.
pub const UNLOADED: u16 = 10;

pub fn has(flags: u16, bit: u16) -> bool {
    flags & bit != 0
}

/// Can the player's body be in this block? Carpets count, you just walk on them.
pub fn passable(flags: u16) -> bool {
    flags & (PASSABLE | CARPET) != 0
}

pub fn offset(p: Pos, dx: i32, dy: i32, dz: i32) -> Pos {
    [p[0] + dx, p[1] + dy, p[2] + dz]
}

pub fn above(p: Pos, blocks: i32) -> Pos {
    offset(p, 0, blocks, 0)
}

pub fn below(p: Pos, blocks: i32) -> Pos {
    offset(p, 0, -blocks, 0)
}

/// The block snapshot Java sends over. `cells` has one entry per block in
/// x, y, z order. `corners` has a bit for each diagonal you can squeeze
/// through (Java works that out from the real block shapes).
pub struct Grid<'a> {
    pub cells: &'a [u16],
    pub corners: &'a [u8],
    pub min: Pos,
    pub size: Pos,
    /// The world's lowest Y and the Y just above its build limit.
    pub world_y: [i32; 2],
}

impl Grid<'_> {
    fn index(&self, p: Pos) -> Option<usize> {
        let x = p[0] as i64 - self.min[0] as i64;
        let y = p[1] as i64 - self.min[1] as i64;
        let z = p[2] as i64 - self.min[2] as i64;
        let inside = |v: i64, size: i32| v >= 0 && v < size as i64;
        if !inside(x, self.size[0]) || !inside(y, self.size[1]) || !inside(z, self.size[2]) {
            return None;
        }
        Some(((x * self.size[1] as i64 + y) * self.size[2] as i64 + z) as usize)
    }

    pub fn flags(&self, p: Pos) -> u16 {
        // Below the world or above the build limit there's only air
        if p[1] < self.world_y[0] || p[1] >= self.world_y[1] {
            return AIR;
        }
        match self.index(p) {
            Some(i) => self.cells[i],
            None => UNLOADED,
        }
    }

    /// Can the player get diagonally from `p` past the corner toward (dx, dz)
    /// even though the two blocks beside it aren't fully open? (e.g. two glass
    /// panes leave a gap at the corner between them)
    pub fn squeezes(&self, p: Pos, dx: i32, dz: i32) -> bool {
        // Same bit order as V5World.captureBatch
        let bit = match (dx > 0, dz < 0) {
            (true, true) => 0,
            (false, true) => 1,
            (true, false) => 2,
            (false, false) => 3,
        };
        match self.index(p) {
            Some(i) => self.corners.get(i).copied().unwrap_or(0) & (1 << bit) != 0,
            None => false,
        }
    }

    /// Can the player stand with their feet in `p`? There has to be a floor
    /// (not a fence) and room for the feet and the head.
    pub fn standable(&self, p: Pos) -> bool {
        let floor = self.flags(below(p, 1));
        has(floor, SOLID)
            && !has(floor, FENCE)
            && passable(self.flags(p))
            && passable(self.flags(above(p, 1)))
    }
}
