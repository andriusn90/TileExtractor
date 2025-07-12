import java.awt.Color
import java.awt.Graphics2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import java.sql.DriverManager

fun paintFullTileMap(
    dbPath: String,
    outputFile: String = "full_tile_map.png",
    plane: Int = 0,
    zoom: Int = 4
) {
    val conn = DriverManager.getConnection("jdbc:sqlite:$dbPath")

    // Auto-detect bounds
    val boundsStmt = conn.createStatement()
    val boundsRS = boundsStmt.executeQuery(
        """
        SELECT 
            MIN(global_x) AS minX, MAX(global_x) AS maxX,
            MIN(global_y) AS minY, MAX(global_y) AS maxY
        FROM tiles WHERE plane = $plane
        """
    )
    val minX = boundsRS.getInt("minX")
    val maxX = boundsRS.getInt("maxX")
    val minY = boundsRS.getInt("minY")
    val maxY = boundsRS.getInt("maxY")
    boundsRS.close()
    boundsStmt.close()

    val width = (maxX - minX + 1) * zoom
    val height = (maxY - minY + 1) * zoom
    println("Rendering map: [$minX,$minY] to [$maxX,$maxY] at zoom=$zoom")

    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val g = image.createGraphics() as Graphics2D

    val stmt = conn.prepareStatement(
        """
        SELECT global_x, global_y, is_walkable, blockN, blockE, blockS, blockW, settings
        FROM tiles WHERE plane = ?
        """
    )
    stmt.setInt(1, plane)

    val rs = stmt.executeQuery()
    while (rs.next()) {
        val x = rs.getInt("global_x")
        val y = rs.getInt("global_y")
        val isWalkable = rs.getBoolean("is_walkable")
        val blockN = rs.getBoolean("blockN")
        val blockE = rs.getBoolean("blockE")
        val blockS = rs.getBoolean("blockS")
        val blockW = rs.getBoolean("blockW")
        val settings = rs.getInt("settings")

        val drawX = (x - minX) * zoom
        val drawY = (maxY - y) * zoom // flip Y-axis

        val finalWalkable = isWalkable

// ✅ Draw base tile color
        g.color = when {
            finalWalkable && settings == 4 -> Color.GRAY
            finalWalkable -> Color.GREEN
            else -> Color.BLACK
        }
        g.fillRect(drawX, drawY, zoom, zoom)

// ✅ Draw block sides ONLY if walkable
        if (finalWalkable) {
            g.color = Color.RED
            val lineThickness = 1

            if (blockN) g.fillRect(drawX, drawY, zoom, lineThickness)
            if (blockE) g.fillRect(drawX + zoom - lineThickness, drawY, lineThickness, zoom)
            if (blockS) g.fillRect(drawX, drawY + zoom - lineThickness, zoom, lineThickness)
            if (blockW) g.fillRect(drawX, drawY, lineThickness, zoom)
        }

// ✅ Debug
        println(
            "Tile ($x,$y) FinalWalkable=$finalWalkable BaseWalkable=$isWalkable " +
                    "Settings=$settings Blocked: ${if (blockN) "N " else ""}${if (blockE) "E " else ""}${if (blockS) "S " else ""}${if (blockW) "W" else ""}"
        )

    }
    rs.close()
    stmt.close()
    conn.close()

    g.dispose()
    ImageIO.write(image, "png", File(outputFile))
    println("✅ Map image saved as $outputFile")
}

fun main() {
    paintFullTileMap(
        dbPath = "rs3_world.db",
        outputFile = "full_tile_map.png",
        plane = 0,
        zoom = 4
    )
}
