use std::cmp::Ordering;
use std::collections::{BinaryHeap, HashMap, HashSet};
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
struct Position {
    x: i32,
    y: i32,
    z: i32,
}
// A position is the player's feet, with one more empty block for the head.
// For now, every block in `walls` is a full solid cube, including the floor.
const MAX_DROP: i32 = 3;

fn body_clear(pos: &Position, walls: &HashSet<Position>) -> bool {
    if pos.x < -1 || pos.x > 6 || pos.z < -4 || pos.z > 4 {
        return false;
    }
    let head = Position {
        x: pos.x,
        y: pos.y + 1,
        z: pos.z,
    };
    !walls.contains(pos) && !walls.contains(&head)
}

fn is_walkable(pos: &Position, walls: &HashSet<Position>) -> bool {
    let below = Position {
        x: pos.x,
        y: pos.y - 1,
        z: pos.z,
    };
    body_clear(pos, walls) && walls.contains(&below)
}

fn neighbors(pos: &Position, walls: &HashSet<Position>) -> Vec<Position> {
    let mut next_positions = Vec::with_capacity(4);
    if !is_walkable(pos, walls) {
        return next_positions;
    }

    for (dx, dz) in [(1, 0), (-1, 0), (0, -1), (0, 1)] {
        let level = Position {
            x: pos.x + dx,
            y: pos.y,
            z: pos.z + dz,
        };

        // Walk straight onto the neighboring floor at the same height.
        if is_walkable(&level, walls) {
            next_positions.push(level);
            continue;
        }

        // Jump onto a block one level higher. Check the room to rise at
        // the source as well as the feet, head and floor at the landing.
        let raised_source = Position {
            x: pos.x,
            y: pos.y + 1,
            z: pos.z,
        };
        let up = Position {
            x: level.x,
            y: pos.y + 1,
            z: level.z,
        };
        if body_clear(&raised_source, walls) && is_walkable(&up, walls) {
            next_positions.push(up);
            continue;
        }

        // Walk off an edge, then fall to the first supported position.
        // The entry and the entire falling column must be clear: checking
        // only the landing could let a route pass through a ceiling.
        if !body_clear(&level, walls) {
            continue;
        }
        for drop in 1..=MAX_DROP {
            let down = Position {
                x: level.x,
                y: pos.y - drop,
                z: level.z,
            };
            if !body_clear(&down, walls) {
                break;
            }
            if is_walkable(&down, walls) {
                next_positions.push(down);
                break;
            }
        }
    }
    next_positions
}

// Simple grid costs, not Minecraft timing: walk = 1, jump up = 2,
// drop = 1 + height lost. Counting both horizontal and vertical distance
// keeps the Manhattan heuristic below or equal to the actual route cost.
fn movement_cost(from: &Position, to: &Position) -> i32 {
    1 + (to.y - from.y).abs()
}

fn heuristic(a: &Position, b: &Position) -> i32 {
    (a.x - b.x).abs() + (a.y - b.y).abs() + (a.z - b.z).abs()
}

#[derive(Debug, PartialEq, Eq)]
struct Node {
    f: i32,
    pos: Position,
}

impl Ord for Node {
    fn cmp(&self, other: &Self) -> Ordering {
        other.f.cmp(&self.f).then_with(|| other.pos.cmp(&self.pos))
    }
}
impl PartialOrd for Node {
    fn partial_cmp(&self, other: &Self) -> Option<Ordering> {
        Some(self.cmp(other))
    }
}

fn reconstruct_path(came_from: &HashMap<Position, Position>, goal: Position) -> Vec<Position> {
    let mut path = vec![goal];
    let mut current = goal;
    while let Some(&prev) = came_from.get(&current) {
        path.push(prev);
        current = prev;
    }
    path.reverse();
    path
}

fn a_star(
    start: Position,
    goal: Position,
    max_nodes: usize,
    walls: &HashSet<Position>,
) -> Option<Vec<Position>> {
    if !is_walkable(&start, walls) || !is_walkable(&goal, walls) {
        return None;
    }
    let mut open = BinaryHeap::new();
    let mut came_from: HashMap<Position, Position> = HashMap::new();
    let mut g_score: HashMap<Position, i32> = HashMap::new();
    let mut closed: HashSet<Position> = HashSet::new();

    g_score.insert(start, 0);
    open.push(Node {
        f: heuristic(&start, &goal),
        pos: start,
    });

    while let Some(Node { pos: current, .. }) = open.pop() {
        if current == goal {
            return Some(reconstruct_path(&came_from, current));
        }
        if !closed.insert(current) {
            continue;
        }
        if closed.len() > max_nodes {
            return None;
        }

        let current_g = g_score[&current];
        for next in neighbors(&current, walls) {
            if closed.contains(&next) {
                continue;
            }
            let tentative_g = current_g + movement_cost(&current, &next);
            if tentative_g < *g_score.get(&next).unwrap_or(&i32::MAX) {
                came_from.insert(next, current);
                g_score.insert(next, tentative_g);
                open.push(Node {
                    f: tentative_g + heuristic(&next, &goal),
                    pos: next,
                });
            }
        }
    }
    None
}

fn print_grid(path: &[Position], start: Position, goal: Position, walls: &HashSet<Position>) {
    let min_y = path
        .iter()
        .map(|p| p.y)
        .min()
        .unwrap_or(start.y)
        .min(goal.y);
    let max_y = path
        .iter()
        .map(|p| p.y)
        .max()
        .unwrap_or(start.y)
        .max(goal.y);
    println!("S = start, G = goal, * = path, # = solid block, . = can stand");
    println!("Blank cells have no standing space or no floor.");
    for y in min_y..=max_y {
        println!("\nFeet Y = {y}");
        for z in -4..=4 {
            let mut row = String::new();
            for x in -1..=6 {
                let p = Position { x, y, z };
                let c = if p == start {
                    'S'
                } else if p == goal {
                    'G'
                } else if path.contains(&p) {
                    '*'
                } else if walls.contains(&p) {
                    '#'
                } else if is_walkable(&p, walls) {
                    '.'
                } else {
                    ' '
                };
                row.push(c);
            }
            println!("{row}");
        }
    }
}

fn main() {
    let mut position = Position { x: 0, y: 64, z: 0 };
    position.x += 1;
    println!("{},{},{}", position.x, position.y, position.z);

    println!("\n--- A* ---");
    let start = Position { x: 0, y: 64, z: 0 };
    let goal = Position { x: 5, y: 64, z: 0 };

    let mut walls = HashSet::new();

    for z in -2..=2 {
        walls.insert(Position { x: 2, y: 64, z });
    }

    for x in -1..=6 {
        for z in -4..=4 {
            walls.insert(Position { x, y: 63, z });
        }
    }

    println!("Valid moves from the practice position:");
    for neighbor in neighbors(&position, &walls) {
        println!("  {},{},{}", neighbor.x, neighbor.y, neighbor.z);
    }

    match a_star(start, goal, 10_000, &walls) {
        Some(path) => {
            let cost: i32 = path
                .windows(2)
                .map(|pair| movement_cost(&pair[0], &pair[1]))
                .sum();
            println!(
                "found path with {} moves, total cost {cost}:",
                path.len() - 1
            );
            println!("  start: {:?}", path[0]);
            for pair in path.windows(2) {
                let action = if pair[1].y > pair[0].y {
                    "jump up"
                } else if pair[1].y < pair[0].y {
                    "drop down"
                } else {
                    "walk"
                };
                println!("  {action}: {:?}", pair[1]);
            }
            print_grid(&path, start, goal, &walls);
        }
        None => println!("no path found"),
    }
}

#[cfg(test)]
mod tests;
