use super::*;

fn p(x: i32, y: i32, z: i32) -> Position {
    Position { x, y, z }
}

fn floor_line(x0: i32, x1: i32, y: i32) -> HashSet<Position> {
    (x0..=x1).map(|x| p(x, y, 0)).collect()
}

fn route(walls: &HashSet<Position>, start: Position, goal: Position) -> Vec<Position> {
    let path = a_star(start, goal, 10_000, walls).expect("expected a route");
    assert_eq!(path.first(), Some(&start));
    assert_eq!(path.last(), Some(&goal));
    for position in &path {
        assert!(is_walkable(position, walls));
    }
    for pair in path.windows(2) {
        assert_eq!(
            (pair[1].x - pair[0].x).abs() + (pair[1].z - pair[0].z).abs(),
            1
        );
        assert!((-MAX_DROP..=1).contains(&(pair[1].y - pair[0].y)));
    }
    path
}

#[test]
fn flat_route_still_works() {
    let walls = floor_line(0, 5, 63);
    let path = route(&walls, p(0, 64, 0), p(5, 64, 0));
    assert_eq!(path.len(), 6);
    assert!(path.iter().all(|p| p.y == 64));
}

#[test]
fn jumps_up_and_back_down_over_the_wall() {
    let mut walls = floor_line(0, 5, 63);
    walls.insert(p(2, 64, 0));
    let path = route(&walls, p(0, 64, 0), p(5, 64, 0));
    assert!(path.contains(&p(2, 65, 0)));
    assert_eq!(
        path.windows(2)
            .map(|pair| movement_cost(&pair[0], &pair[1]))
            .sum::<i32>(),
        7
    );
}

#[test]
fn jumps_in_all_four_directions() {
    for (dx, dz) in [(1, 0), (-1, 0), (0, 1), (0, -1)] {
        let start = p(0, 64, 0);
        let goal = p(dx, 65, dz);
        let walls = HashSet::from([p(0, 63, 0), p(dx, 64, dz)]);
        assert_eq!(route(&walls, start, goal), vec![start, goal]);
    }
}

#[test]
fn climbs_and_descends_a_staircase_to_different_y_goals() {
    let walls: HashSet<_> = (0..=5).map(|x| p(x, 63 + x, 0)).collect();
    let up = route(&walls, p(0, 64, 0), p(5, 69, 0));
    assert!(up.windows(2).all(|pair| pair[1].y == pair[0].y + 1));
    let down = route(&walls, p(5, 69, 0), p(0, 64, 0));
    assert!(down.windows(2).all(|pair| pair[1].y == pair[0].y - 1));
}

#[test]
fn low_ceiling_at_takeoff_blocks_the_jump() {
    let mut walls = floor_line(0, 1, 63);
    walls.insert(p(2, 64, 0));
    walls.insert(p(1, 66, 0));
    assert!(is_walkable(&p(1, 64, 0), &walls));
    assert!(is_walkable(&p(2, 65, 0), &walls));
    assert!(a_star(p(0, 64, 0), p(2, 65, 0), 100, &walls).is_none());
}

#[test]
fn refuses_a_two_block_climb() {
    let walls = HashSet::from([p(0, 63, 0), p(1, 65, 0)]);
    assert!(is_walkable(&p(1, 66, 0), &walls));
    assert!(a_star(p(0, 64, 0), p(1, 66, 0), 100, &walls).is_none());
}

#[test]
fn drops_one_two_or_three_blocks() {
    for drop in 1..=3 {
        let start = p(0, 64, 0);
        let goal = p(1, 64 - drop, 0);
        let walls = HashSet::from([p(0, 63, 0), p(1, 63 - drop, 0)]);
        assert_eq!(route(&walls, start, goal), vec![start, goal]);
    }
}

#[test]
fn refuses_a_four_block_drop() {
    let walls = HashSet::from([p(0, 63, 0), p(1, 59, 0)]);
    assert!(a_star(p(0, 64, 0), p(1, 60, 0), 100, &walls).is_none());
}

#[test]
fn drop_needs_headroom_at_the_edge() {
    let walls = HashSet::from([p(0, 63, 0), p(1, 60, 0), p(1, 65, 0)]);
    assert!(is_walkable(&p(1, 61, 0), &walls));
    assert!(a_star(p(0, 64, 0), p(1, 61, 0), 100, &walls).is_none());
}

#[test]
fn cannot_fall_through_an_intermediate_floor() {
    let walls = HashSet::from([p(0, 66, 0), p(1, 66, 0), p(1, 63, 0)]);
    assert!(is_walkable(&p(1, 64, 0), &walls));
    assert!(a_star(p(0, 67, 0), p(1, 64, 0), 100, &walls).is_none());
    assert_eq!(neighbors(&p(0, 67, 0), &walls), vec![p(1, 67, 0)]);
}

#[test]
fn no_floating_or_gap_jumps() {
    let walls = HashSet::from([p(0, 63, 0), p(2, 63, 0)]);
    assert!(a_star(p(0, 64, 0), p(2, 64, 0), 100, &walls).is_none());
    assert!(!is_walkable(&p(1, 64, 0), &walls));
}

#[test]
fn invalid_endpoints_and_limits_are_preserved() {
    let walls = floor_line(-1, 6, 63);
    assert!(a_star(p(0, 64, 0), p(5, 64, 0), 0, &walls).is_none());
    assert!(a_star(p(0, 64, 0), p(7, 64, 0), 100, &walls).is_none());
    assert!(a_star(p(0, 63, 0), p(0, 63, 0), 100, &walls).is_none());
    assert_eq!(
        a_star(p(0, 64, 0), p(0, 64, 0), 100, &walls),
        Some(vec![p(0, 64, 0)])
    );
}

#[test]
fn movement_costs_keep_the_heuristic_consistent() {
    let walls: HashSet<_> = (0..=5).map(|x| p(x, 63 + x, 0)).collect();
    for x in 0..=5 {
        let current = p(x, 64 + x, 0);
        for next in neighbors(&current, &walls) {
            for goal_x in 0..=5 {
                let goal = p(goal_x, 64 + goal_x, 0);
                assert!(
                    heuristic(&current, &goal)
                        <= movement_cost(&current, &next) + heuristic(&next, &goal)
                );
            }
        }
    }
}

#[test]
fn heap_ties_match_equality_and_smallest_cost_is_first() {
    let a = Node {
        f: 5,
        pos: p(0, 64, 0),
    };
    let b = Node {
        f: 5,
        pos: p(1, 64, 0),
    };
    assert_ne!(a.cmp(&b), Ordering::Equal);
    let mut heap = BinaryHeap::from([
        a,
        b,
        Node {
            f: 3,
            pos: p(2, 64, 0),
        },
    ]);
    assert_eq!(heap.pop().unwrap().f, 3);
}
