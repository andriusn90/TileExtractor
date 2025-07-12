//import com.google.gson.Gson
//import com.google.gson.reflect.TypeToken
//import java.io.File
//import java.sql.DriverManager
//
//data class Tile(
//    val shape: Int?,
//    val overlay_id: Int?,
//    val underlay_id: Int?,
//    val settings: Int?,
//    val height: Int?
//)
//
//data class ObjectLocation(
//    val plane: Int,
//    val i: Int,
//    val j: Int,
//    val x: Int,
//    val y: Int,
//    val id: Int,
//    val type: Int,
//    val rotation: Int
//)
//
//data class ObjectDefinition(
//    val id: Int,
//    val name: String,
//    val dim_x: Int = 1,
//    val dim_y: Int = 1,
//    val actions: List<String?>? = null,
//    val models: Any? = null,
//    val unknown_21: Boolean? = null,
//    val occludes_2: Boolean? = null,
//    val unknown_186: Int? = null,
//    val unknown_196: Int? = null,
//    val unknown_22: Boolean? = null,
//    val is_transparent: Boolean? = null
//)
//
//data class TileFlags(
//    var blockN: Boolean = false,
//    var blockE: Boolean = false,
//    var blockS: Boolean = false,
//    var blockW: Boolean = false,
//    var forceWalkable: Boolean = false,
//    val debugReasons: MutableList<String> = mutableListOf()
//)
//
//fun main() {
//    val gson = Gson()
//    val conn = DriverManager.getConnection("jdbc:sqlite:rs3_world.db")
//    conn.autoCommit = false
//
//    conn.createStatement().apply {
//        execute("""
//            CREATE TABLE IF NOT EXISTS tiles (
//                global_x INTEGER,
//                global_y INTEGER,
//                plane INTEGER,
//                height INTEGER,
//                overlay_id INTEGER,
//                underlay_id INTEGER,
//                settings INTEGER,
//                is_walkable BOOLEAN,
//                blockN BOOLEAN,
//                blockE BOOLEAN,
//                blockS BOOLEAN,
//                blockW BOOLEAN,
//                debug_reason TEXT,
//                PRIMARY KEY(global_x, global_y, plane)
//            );
//        """)
//        execute("CREATE TABLE IF NOT EXISTS objects (object_id INTEGER PRIMARY KEY, name TEXT, dim_x INTEGER, dim_y INTEGER, actions TEXT)")
//        execute("CREATE TABLE IF NOT EXISTS object_locations (id INTEGER PRIMARY KEY AUTOINCREMENT, object_id INTEGER, global_x INTEGER, global_y INTEGER, plane INTEGER, rotation INTEGER, FOREIGN KEY(object_id) REFERENCES objects(object_id))")
//    }
//
//    val minI = 45
//    val maxI = 55
//    val minJ = 48
//    val maxJ = 56
//    val validPlanes = setOf(0, 1)
//
//    val objDefs = mutableMapOf<Int, ObjectDefinition>()
//    File("extracted/location_configs").listFiles()?.forEach { file ->
//        val obj = gson.fromJson<ObjectDefinition>(file.readText(), ObjectDefinition::class.java)
//        objDefs[obj.id] = obj
//        conn.prepareStatement("INSERT OR IGNORE INTO objects VALUES (?,?,?,?,?)").use { stmt ->
//            stmt.setInt(1, obj.id)
//            stmt.setString(2, obj.name)
//            stmt.setInt(3, obj.dim_x)
//            stmt.setInt(4, obj.dim_y)
//            stmt.setString(5, gson.toJson(obj.actions ?: listOf<String>()))
//            stmt.execute()
//        }
//    }
//
//    val tileFlags = mutableMapOf<Triple<Int, Int, Int>, TileFlags>()
//
//    File("extracted/locations").listFiles()?.forEach { file ->
//        val locs = gson.fromJson<List<ObjectLocation>>(file.readText(), object : TypeToken<List<ObjectLocation>>() {}.type)
//        locs.forEach { loc ->
//            if (loc.i in minI..maxI && loc.j in minJ..maxJ) {
//                val globalX = (loc.i shl 6) + loc.x
//                val globalY = (loc.j shl 6) + loc.y
//                val plane = loc.plane
//                if (plane !in validPlanes) return@forEach
//
//                val objDef = objDefs[loc.id] ?: return@forEach
//
//                conn.prepareStatement("INSERT INTO object_locations (object_id, global_x, global_y, plane, rotation) VALUES (?,?,?,?,?)").use { stmt ->
//                    stmt.setInt(1, loc.id)
//                    stmt.setInt(2, globalX)
//                    stmt.setInt(3, globalY)
//                    stmt.setInt(4, plane)
//                    stmt.setInt(5, loc.rotation)
//                    stmt.execute()
//                }
//
//                if (objDef.id == 45156) {
//                    val finalDimX = if (objDef.dim_x > 0) objDef.dim_x else 1
//                    val finalDimY = if (objDef.dim_y > 0) objDef.dim_y else 1
//
//                    for (dx in 0 until finalDimX) {
//                        for (dy in 0 until finalDimY) {
//                            val key = Triple(globalX + dx, globalY + dy, plane)
//                            tileFlags.getOrPut(key) { TileFlags() }.apply {
//                                forceWalkable = true
//                                debugReasons += "Override: Object 45156 forced walkable"
//                            }
//                        }
//                    }
//                    return@forEach
//                }
//
//
//
//                    if (objDef.unknown_196 == 1) {
//                        println("Bridge override for model=false: ${objDef.id} at ($globalX,$globalY,$plane)")
//                        // treat it as passable like a bridge floor:
//                        for (dx in 0 until (objDef.dim_x.takeIf { it > 0 } ?: 1)) {
//                            for (dy in 0 until (objDef.dim_y.takeIf { it > 0 } ?: 1)) {
//                                val key = Triple(globalX + dx, globalY + dy, plane)
//                                tileFlags.getOrPut(key) { TileFlags() }.debugReasons += "BridgeOverride: model=false unknown_196==1"
//                            }
//                        }
//                        // ✅ Do NOT return here — keep going, no blocking sides for bridge deck!
//                    } else {
//                        return@forEach
//                    }
//                    if (objDef.unknown_22 == true && objDef.occludes_2 == false) {
//                        println("Bridge override for model=false & unknown_22=true: ${objDef.id} at ($globalX,$globalY,$plane)")
//                        for (dx in 0 until objDef.dim_x) {
//                            for (dy in 0 until objDef.dim_y) {
//                                val key = Triple(globalX + dx, globalY + dy, plane)
//                                tileFlags.getOrPut(key) { TileFlags() }.apply {
//                                    forceWalkable = true
//                                    debugReasons += "BridgeOverride: model=false & unknown_22==true"
//                                }
//                            }
//                        }
//                        return@forEach // Skip normal blocking sides — this is walkable bridge surface
//                    }
//
//
//                if (objDef.occludes_2 == true || (objDef.unknown_186 != null && objDef.unknown_186 >= 1)) {
//                    val key = Triple(globalX, globalY, plane)
//                    tileFlags.getOrPut(key) { TileFlags() }.debugReasons += "Roof: Object:${loc.id}"
//                    return@forEach
//                }
//
//                // ✅ NEW: bridge floor forced walkable if unknown_21 true and occludes_2 false
//                if (objDef.unknown_21 == true && objDef.occludes_2 == false) {
//                    for (dx in 0 until objDef.dim_x) {
//                        for (dy in 0 until objDef.dim_y) {
//                            val key = Triple(globalX + dx, globalY + dy, plane)
//                            tileFlags.getOrPut(key) { TileFlags() }.apply {
//                                forceWalkable = true
//                                debugReasons += "BridgeDeck: ${loc.id}"
//                            }
//                        }
//                    }
//                    return@forEach
//                }
//
////                // ✅ NEW: bridge override for transparent floor above water
////                if (objDef.is_transparent == true && objDef.occludes_2 == false) {
////                    println("Bridge override for is_transparent+occludes_2: ${objDef.id} at ($globalX,$globalY,$plane)")
////
////                    // Force at least 1x1 even if dimensions are missing or zero
////                    val finalDimX = objDef.dim_x.takeIf { it > 0 } ?: 1
////                    val finalDimY = objDef.dim_y.takeIf { it > 0 } ?: 1
////
////                    for (dx in 0 until finalDimX) {
////                        for (dy in 0 until finalDimY) {
////                            val key = Triple(globalX + dx, globalY + dy, plane)
////                            tileFlags.getOrPut(key) { TileFlags() }.apply {
////                                forceWalkable = true
////                                debugReasons += "BridgeOverride: is_transparent=true occludes_2=false dim=${finalDimX}x${finalDimY}"
////                            }
////                        }
////                    }
////
////                    // ✅ This ensures you skip normal wall blocking for these tiles!
////                    return@forEach
////                }
//
//                if (objDef.dim_x == 0 && objDef.dim_y == 0) {
//                    val key = Triple(globalX, globalY, plane)
//                    val flags = tileFlags.getOrPut(key) { TileFlags() }
//                    flags.debugReasons += "Object:${loc.id} rotation:${loc.rotation} dim0"
//
//                    when (loc.rotation) {
//                        1 -> {
//                            flags.blockN = true; tileFlags.getOrPut(Triple(globalX, globalY + 1, plane)) { TileFlags() }.apply {
//                                blockS = true; debugReasons += "Neighbor S by object ${loc.id}"
//                            }
//                        }
//                        2 -> {
//                            flags.blockE = true; tileFlags.getOrPut(Triple(globalX + 1, globalY, plane)) { TileFlags() }.apply {
//                                blockW = true; debugReasons += "Neighbor W by object ${loc.id}"
//                            }
//                        }
//                        3 -> {
//                            flags.blockS = true; tileFlags.getOrPut(Triple(globalX, globalY - 1, plane)) { TileFlags() }.apply {
//                                blockN = true; debugReasons += "Neighbor N by object ${loc.id}"
//                            }
//                        }
//                        4, 0 -> {
//                            flags.blockW = true; tileFlags.getOrPut(Triple(globalX - 1, globalY, plane)) { TileFlags() }.apply {
//                                blockE = true; debugReasons += "Neighbor E by object ${loc.id}"
//                            }
//                        }
//                    }
//                    return@forEach
//                }
//
//                for (dx in 0 until objDef.dim_x) {
//                    for (dy in 0 until objDef.dim_y) {
//                        val x = globalX + dx
//                        val y = globalY + dy
//                        val key = Triple(x, y, plane)
//                        val flags = tileFlags.getOrPut(key) { TileFlags() }
//                        flags.debugReasons += "Object:${loc.id} rotation:${loc.rotation} dim:${objDef.dim_x}x${objDef.dim_y}"
//
//                        when (loc.rotation) {
//                            1 -> {
//                                flags.blockN = true; tileFlags.getOrPut(Triple(x, y + 1, plane)) { TileFlags() }.apply {
//                                    blockS = true; debugReasons += "Neighbor S by object ${loc.id}"
//                                }
//                            }
//                            2 -> {
//                                flags.blockE = true; tileFlags.getOrPut(Triple(x + 1, y, plane)) { TileFlags() }.apply {
//                                    blockW = true; debugReasons += "Neighbor W by object ${loc.id}"
//                                }
//                            }
//                            3 -> {
//                                flags.blockS = true; tileFlags.getOrPut(Triple(x, y - 1, plane)) { TileFlags() }.apply {
//                                    blockN = true; debugReasons += "Neighbor N by object ${loc.id}"
//                                }
//                            }
//                            4 -> {
//                                flags.blockW = true; tileFlags.getOrPut(Triple(x - 1, y, plane)) { TileFlags() }.apply {
//                                    blockE = true; debugReasons += "Neighbor E by object ${loc.id}"
//                                }
//                            }
//                        }
//                    }
//                }
//            }
//        }
//    }
//
//    File("extracted/tiles").listFiles()?.forEach { file ->
//        val (i, j) = file.nameWithoutExtension.split("_").map { it.toInt() }
//        if (i in minI..maxI && j in minJ..maxJ) {
//            val json = gson.fromJson<Map<String, Any>>(file.readText(), object : TypeToken<Map<String, Any>>() {}.type)
//            val dim = json["dim"] as List<*>
//            val tiles = gson.fromJson<List<Tile>>(gson.toJson(json["data"]), object : TypeToken<List<Tile>>() {}.type)
//            var idx = 0
//            for (plane in 0 until (dim[0] as Double).toInt()) {
//                if (plane !in validPlanes) continue
//
//                for (x in 0 until (dim[1] as Double).toInt()) {
//                    for (y in 0 until (dim[2] as Double).toInt()) {
//                        val tile = tiles[idx++]
//                        val globalX = (i shl 6) + x
//                        val globalY = (j shl 6) + y
//                        val key = Triple(globalX, globalY, plane)
//                        val flags = tileFlags[key]
//
//                        val baseWalkableSettings = setOf(0, 2, 3, 4, 5, 8)
//                        val isBaseWalkable = (tile.settings in baseWalkableSettings || tile.settings == null) && plane == 0
//                        val isWalkable = flags?.forceWalkable == true || isBaseWalkable
//
//                        val selectStmt = conn.prepareStatement("""
//                            SELECT blockN, blockE, blockS, blockW, debug_reason FROM tiles
//                            WHERE global_x=? AND global_y=? AND plane=?
//                        """)
//                        selectStmt.setInt(1, globalX)
//                        selectStmt.setInt(2, globalY)
//                        selectStmt.setInt(3, plane)
//                        val rs = selectStmt.executeQuery()
//
////                        val existingBlockN = if (rs.next()) rs.getBoolean("blockN") else false
////                        val existingBlockE = if (rs.isClosed) false else rs.getBoolean("blockE")
////                        val existingBlockS = if (rs.isClosed) false else rs.getBoolean("blockS")
////                        val existingBlockW = if (rs.isClosed) false else rs.getBoolean("blockW")
////                        val existingReasons = if (rs.isClosed) "" else rs.getString("debug_reason") ?: ""
//                        val hasRow = rs.next()
//                        val existingBlockN = if (hasRow) rs.getBoolean("blockN") else false
//                        val existingBlockE = if (hasRow) rs.getBoolean("blockE") else false
//                        val existingBlockS = if (hasRow) rs.getBoolean("blockS") else false
//                        val existingBlockW = if (hasRow) rs.getBoolean("blockW") else false
//                        val existingReasons = if (hasRow) rs.getString("debug_reason") ?: "" else ""
//                        rs.close()
//                        selectStmt.close()
//
//                        val finalBlockN = (flags?.blockN ?: false) || existingBlockN
//                        val finalBlockE = (flags?.blockE ?: false) || existingBlockE
//                        val finalBlockS = (flags?.blockS ?: false) || existingBlockS
//                        val finalBlockW = (flags?.blockW ?: false) || existingBlockW
//                        val finalReasons = (flags?.debugReasons ?: emptyList()) + if (existingReasons.isNotBlank()) listOf(existingReasons) else emptyList()
//
//                        conn.prepareStatement("INSERT OR REPLACE INTO tiles VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)").use { stmt ->
//                            stmt.setInt(1, globalX)
//                            stmt.setInt(2, globalY)
//                            stmt.setInt(3, plane)
//                            stmt.setInt(4, tile.height ?: 0)
//                            stmt.setInt(5, tile.overlay_id ?: -1)
//                            stmt.setInt(6, tile.underlay_id ?: -1)
//                            stmt.setInt(7, tile.settings ?: 0)
//                            stmt.setBoolean(8, isWalkable)
//                            stmt.setBoolean(9, finalBlockN)
//                            stmt.setBoolean(10, finalBlockE)
//                            stmt.setBoolean(11, finalBlockS)
//                            stmt.setBoolean(12, finalBlockW)
//                            stmt.setString(13, finalReasons.joinToString("; "))
//                            stmt.execute()
//                        }
//
//                        println("Tile ($globalX,$globalY,$plane) Walkable:$isWalkable Debug: ${finalReasons.joinToString()}")
//                    }
//                }
//            }
//        }
//    }
//
//    conn.commit()
//    conn.close()
//    println("✅ Done!")
//    paintFullTileMap(
//        dbPath = "rs3_world.db",
//        outputFile = "full_tile_map.png",
//        plane = 0,
//        zoom = 4
//    )
//}
