import java.sql.Connection
import java.sql.DriverManager
import java.util.*
import kotlin.math.abs

//fun main() {
//    val conn = DriverManager.getConnection("jdbc:sqlite:rs3_world.db")
//    findPath(conn, startX = 2967, startY = 3409, destX = 3167, destY = 3458)
//    conn.close()
//}

fun findPath(conn: Connection, startX: Int, startY: Int, destX: Int, destY: Int, plane: Int = 0) {
    data class Node(
        val x: Int,
        val y: Int,
        val cost: Int,
        val path: List<Pair<Int, Int>>,
        val lastDir: Pair<Int, Int>?,
        val stepsInDir: Int
    )

    val open = PriorityQueue<Node>(compareBy { it.cost })
    val visited = mutableSetOf<Pair<Int, Int>>()

    open.add(Node(startX, startY, 0, listOf(Pair(startX, startY)), null, 0))

    val directions = listOf(
        Pair(1, 0), Pair(-1, 0),
        Pair(0, 1), Pair(0, -1),
        Pair(1, 1), Pair(-1, 1),
        Pair(1, -1), Pair(-1, -1)
    )

    while (open.isNotEmpty()) {
        val current = open.poll()

        if (current.x == destX && current.y == destY) {
            println("Path waypoints:")
            current.path.forEach { println("Coordinate(${it.first}, ${it.second}, $plane),") }
            return
        }

        for ((dx, dy) in directions) {
            val nx = current.x + dx
            val ny = current.y + dy
            val key = Pair(nx, ny)
            if (visited.contains(key)) continue

            if (!isTileWalkable(conn, nx, ny, plane)) continue

            // Diagonal: check corner clipping
            if (dx != 0 && dy != 0) {
                if (!isTileWalkable(conn, current.x + dx, current.y, plane)) continue
                if (!isTileWalkable(conn, current.x, current.y + dy, plane)) continue
            }

            visited.add(key)

            // Heuristic cost to destination (Manhattan distance)
            val heuristic = abs(destX - nx) + abs(destY - ny)

            // Clearance cost: more obstacles around => higher penalty
            val clearancePenalty = countObstaclesAround(conn, nx, ny, plane) * 5

            // Total cost: cost so far + clearance + heuristic
            val sameDir = current.lastDir == Pair(dx, dy)
            val newStepsInDir = if (sameDir) current.stepsInDir + 1 else 1
            val stepCost = 1 + clearancePenalty
            val newCost = current.cost + stepCost + heuristic

            // Add waypoint if direction changed or gone ~15 tiles straight
            val addWaypoint = !sameDir || newStepsInDir >= 15
            val newPath = if (addWaypoint) current.path + Pair(nx, ny) else current.path

            open.add(Node(nx, ny, newCost, newPath, Pair(dx, dy), newStepsInDir))
        }
    }

    println("No path found.")
}

fun isTileWalkable(conn: Connection, x: Int, y: Int, plane: Int): Boolean {
    val stmt = conn.prepareStatement("SELECT is_walkable FROM tiles WHERE global_x=? AND global_y=? AND plane=?")
    stmt.setInt(1, x)
    stmt.setInt(2, y)
    stmt.setInt(3, plane)
    val rs = stmt.executeQuery()
    val walkable = rs.next() && rs.getBoolean(1)
    rs.close()
    stmt.close()
    return walkable
}

fun countObstaclesAround(conn: Connection, x: Int, y: Int, plane: Int): Int {
    var count = 0
    for (dx in -1..1) {
        for (dy in -1..1) {
            if (dx == 0 && dy == 0) continue
            if (!isTileWalkable(conn, x + dx, y + dy, plane)) count++
        }
    }
    return count
}
