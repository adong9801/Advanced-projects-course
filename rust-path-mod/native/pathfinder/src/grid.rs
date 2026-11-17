//! Dense block grid produced by `BlockGridSerializer.java`.
//!
//! Every block is one `u16`:
//!   bit 0      walkable  (player body can occupy it)
//!   bit 1      standable (has a top surface to stand on)
//!   bit 2      partial   (slab / stair / path / snow layer...)
//!   bit 3      ice
//!   bit 4      stair (stored at half height like a slab, but its back step
//!              is a full block, so stair->stair is a 0.5 rise the grid reads as 1.0)
//!   bits 5-15  collision top as a fraction of a block, `enc / 1024.0` (0.0 ..= 1.999)
//!
//! Java resolves every data source (live chunk, cached bitmap, cached column
//! heights) into this one format, so the search never special-cases
//! unloaded terrain. Unknown blocks and anything outside the grid read as 0:
//! not walkable and not standable, i.e. a wall the search routes around.

pub const WALKABLE: u16 = 1 << 0;
pub const STANDABLE: u16 = 1 << 1;
pub const PARTIAL: u16 = 1 << 2;
pub const ICE: u16 = 1 << 3;
pub const STAIR: u16 = 1 << 4;
pub const HEIGHT_SHIFT: u16 = 5;
pub const HEIGHT_SCALE: f64 = 1024.0;

pub struct Grid<'a> {
    blocks: &'a [u16],
    pub min_x: i32,
    pub min_y: i32,
    pub min_z: i32,
    pub size_x: i32,
    pub size_y: i32,
    pub size_z: i32,
}

impl<'a> Grid<'a> {
    /// Returns `None` if the dimensions don't match the buffer.
    pub fn new(
        blocks: &'a [u16],
        min: (i32, i32, i32),
        size: (i32, i32, i32),
    ) -> Option<Self> {
        let (sx, sy, sz) = size;
        if sx <= 0 || sy <= 0 || sz <= 0 {
            return None;
        }
        let volume = (sx as usize).checked_mul(sy as usize)?.checked_mul(sz as usize)?;
        if volume != blocks.len() {
            return None;
        }
        Some(Grid {
            blocks,
            min_x: min.0,
            min_y: min.1,
            min_z: min.2,
            size_x: sx,
            size_y: sy,
            size_z: sz,
        })
    }

    #[inline]
    pub fn get(&self, x: i32, y: i32, z: i32) -> u16 {
        let lx = x.wrapping_sub(self.min_x);
        let ly = y.wrapping_sub(self.min_y);
        let lz = z.wrapping_sub(self.min_z);
        if (lx as u32) >= self.size_x as u32
            || (ly as u32) >= self.size_y as u32
            || (lz as u32) >= self.size_z as u32
        {
            return 0;
        }
        self.blocks[((lx * self.size_y + ly) * self.size_z + lz) as usize]
    }

    #[inline]
    pub fn walkable(&self, x: i32, y: i32, z: i32) -> bool {
        self.get(x, y, z) & WALKABLE != 0
    }

    #[inline]
    pub fn standable(&self, x: i32, y: i32, z: i32) -> bool {
        self.get(x, y, z) & STANDABLE != 0
    }

    #[inline]
    pub fn partial(&self, x: i32, y: i32, z: i32) -> bool {
        self.get(x, y, z) & PARTIAL != 0
    }

    #[inline]
    pub fn stair(&self, x: i32, y: i32, z: i32) -> bool {
        self.get(x, y, z) & STAIR != 0
    }

    /// Absolute Y of the block's collision top (`y` itself for air).
    #[inline]
    pub fn block_height(&self, x: i32, y: i32, z: i32) -> f64 {
        let enc = self.get(x, y, z) >> HEIGHT_SHIFT;
        y as f64 + enc as f64 / HEIGHT_SCALE
    }

    // ── Derived helpers, one-to-one with MovementHelper.java ─────────────

    /// A full block below the feet, or a partial block the feet are inside.
    #[inline]
    pub fn has_ground_support(&self, x: i32, y: i32, z: i32) -> bool {
        self.standable(x, y - 1, z) || self.standable(x, y, z)
    }

    /// The node stands *on* a partial block at its own Y (stair / bottom slab).
    #[inline]
    pub fn is_partial_block_ground(&self, x: i32, y: i32, z: i32) -> bool {
        self.standable(x, y, z) && !self.standable(x, y - 1, z)
    }

    #[inline]
    pub fn surface_height(&self, x: i32, node_y: i32, z: i32) -> f64 {
        let ground_y = if self.standable(x, node_y, z) { node_y } else { node_y - 1 };
        self.block_height(x, ground_y, z)
    }

    #[inline]
    pub fn is_ice_ground(&self, x: i32, y: i32, z: i32) -> bool {
        (self.get(x, y - 1, z) | self.get(x, y, z)) & ICE != 0
    }

    /// Stable key for a position inside the grid (the search never leaves it,
    /// since outside cells are unwalkable).
    #[inline]
    pub fn key(&self, x: i32, y: i32, z: i32) -> u64 {
        let lx = (x - self.min_x) as u64 & 0x1F_FFFF;
        let ly = (y - self.min_y) as u64 & 0x1F_FFFF;
        let lz = (z - self.min_z) as u64 & 0x1F_FFFF;
        (lx << 42) | (ly << 21) | lz
    }
}

/// Test helper: build a mutable grid in memory.
#[cfg(test)]
pub struct GridBuilder {
    pub blocks: Vec<u16>,
    pub min: (i32, i32, i32),
    pub size: (i32, i32, i32),
}

#[cfg(test)]
impl GridBuilder {
    pub const AIR: u16 = WALKABLE;
    pub const STONE: u16 = STANDABLE | (1024 << HEIGHT_SHIFT);

    pub fn new(min: (i32, i32, i32), size: (i32, i32, i32)) -> Self {
        let n = (size.0 * size.1 * size.2) as usize;
        GridBuilder { blocks: vec![Self::AIR; n], min, size }
    }

    pub fn set(&mut self, x: i32, y: i32, z: i32, v: u16) {
        let lx = x - self.min.0;
        let ly = y - self.min.1;
        let lz = z - self.min.2;
        let i = ((lx * self.size.1 + ly) * self.size.2 + lz) as usize;
        self.blocks[i] = v;
    }

    pub fn fill(&mut self, from: (i32, i32, i32), to: (i32, i32, i32), v: u16) {
        for x in from.0..=to.0 {
            for y in from.1..=to.1 {
                for z in from.2..=to.2 {
                    self.set(x, y, z, v);
                }
            }
        }
    }

    pub fn grid(&self) -> Grid<'_> {
        Grid::new(&self.blocks, self.min, self.size).unwrap()
    }
}
