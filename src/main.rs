use std::cmp::Ordering;
use std::collections::{BinaryHeap, HashMap, HashSet};
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
struct Position{
    x: i32,
    y: i32,
    z: i32,

}
fn neighbors(pos: &Position) -> [Position; 4] {
    [
        Position {
            x: pos.x + 1,
            y: pos.y,
            z: pos.z,
        },
        Position {
            x: pos.x - 1,
            y: pos.y,
            z: pos.z,
        },
        Position {
            x: pos.x,
            y: pos.y,
            z: pos.z-1,
        },
        Position {
            x: pos.x,
            y: pos.y,
            z: pos.z + 1,
        },
    ]
}
fn is_walkable(pos: &Position) -> bool{
    if pos.x == 2 && pos.y == 64 && matches!(pos.z, -2..=2) {
        return false;
    } else {
        return true;
    }

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
        other.f.cmp(&self.f)
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

fn a_star(start: Position, goal: Position, max_nodes: usize) -> Option<Vec<Position>> {
    let mut open = BinaryHeap::new();
    let mut came_from: HashMap<Position, Position> = HashMap::new();
    let mut g_score: HashMap<Position, i32> = HashMap::new();
    let mut closed: HashSet<Position> = HashSet::new();

    g_score.insert(start, 0);
    open.push(Node { f: heuristic(&start, &goal), pos: start });

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
        for next in neighbors(&current) {
            if !is_walkable(&next) || closed.contains(&next) {
                continue;
            }
            let tentative_g = current_g + 1;
            if tentative_g < *g_score.get(&next).unwrap_or(&i32::MAX) {
                came_from.insert(next, current);
                g_score.insert(next, tentative_g);
                open.push(Node { f: tentative_g + heuristic(&next, &goal), pos: next });
            }
        }
    }
    None
}

fn print_grid(path: &[Position], start: Position, goal: Position) {
    for z in -4..=4 {
        let mut row = String::new();
        for x in -1..=6 {
            let p = Position { x, y: 64, z };
            let c = if p == start { 'S' }
                else if p == goal { 'G' }
                else if !is_walkable(&p) { '#' }
                else if path.contains(&p) { '*' }
                else { '.' };
            row.push(c);
        }
        println!("{}", row);
    }
}


fn main() {
    let mut position = Position{
        x: 0,
        y: 64,
        z: 0,
        };
    position.x += 1;
    println!("{},{},{}", position.x, position.y ,position.z);

    let next_positions= neighbors(&position);
    for neighbor in next_positions {
        println!("{},{},{}", neighbor.x, neighbor.y ,neighbor.z);
    }

    println!("\n--- A* ---");
    let start = Position { x: 0, y: 64, z: 0 };
    let goal = Position { x: 5, y: 64, z: 0 };

    match a_star(start, goal, 10_000) {
        Some(path) => {
            println!("found path with {} steps:", path.len() - 1);
            for p in &path {
                println!("  {:?}", p);
            }
            print_grid(&path, start, goal);
        }
        None => println!("no path found"),
    }
}
