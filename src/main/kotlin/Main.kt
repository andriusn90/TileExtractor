//import com.google.gson.Gson
//import com.google.gson.JsonElement
//import com.google.gson.JsonObject
//import com.google.gson.JsonParser
//import com.google.gson.reflect.TypeToken
//import java.io.File
//import java.sql.DriverManager
//
//val gson = Gson()
//
//fun parseJsonAllowingDuplicates(jsonStr: String): Map<String, Any?> {
//    val root = JsonParser.parseString(jsonStr)
//    if (root !is JsonObject) throw IllegalArgumentException("Not an object JSON")
//
//    val result = mutableMapOf<String, Any?>()
//
//    root.entrySet().forEach { (key, value) ->
//        if (result.containsKey(key)) {
//            val existing = result[key]
//            if (existing is List<*>) {
//                result[key] = existing + gsonElementToKotlin(value)
//            } else {
//                result[key] = listOf(existing, gsonElementToKotlin(value))
//            }
//        } else {
//            result[key] = gsonElementToKotlin(value)
//        }
//    }
//
//    return result
//}
//
//fun gsonElementToKotlin(element: JsonElement): Any? {
//    return when {
//        element.isJsonNull -> null
//        element.isJsonPrimitive -> {
//            val prim = element.asJsonPrimitive
//            when {
//                prim.isBoolean -> prim.asBoolean
//                prim.isNumber -> prim.asNumber
//                prim.isString -> prim.asString
//                else -> prim.toString()
//            }
//        }
//        element.isJsonArray -> element.asJsonArray.map { gsonElementToKotlin(it) }
//        element.isJsonObject -> element.asJsonObject.entrySet().associate { (k, v) -> k to gsonElementToKotlin(v) }
//        else -> null
//    }
//}
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
//    val conn = DriverManager.getConnection("jdbc:sqlite:rs3_world.db")
//    conn.autoCommit = false
//
//    val allObjectFlags = mutableSetOf<String>()
//    val objectDefs = mutableMapOf<Int, Map<String, Any?>>()
//
//    // 1️⃣ Scan all unique keys in objects
//    File("extracted/location_configs").listFiles()?.forEach { file ->
//        val obj = parseJsonAllowingDuplicates(file.readText())
//        allObjectFlags += obj.keys
//        objectDefs[(obj["id"] as Number).toInt()] = obj
//    }
//
//    val baseCols = listOf("object_id", "name", "dim_x", "dim_y", "actions", "models_present")
//    val dynamicCols = allObjectFlags - baseCols.toSet()
//
//    conn.createStatement().apply {
//        execute("DROP TABLE IF EXISTS objects")
//        execute("DROP TABLE IF EXISTS object_locations")
//        execute("DROP TABLE IF EXISTS tiles")
//
//        execute("""
//            CREATE TABLE objects (
//                object_id INTEGER PRIMARY KEY,
//                name TEXT,
//                dim_x INTEGER,
//                dim_y INTEGER,
//                actions TEXT,
//                models_present BOOLEAN,
//                ${dynamicCols.joinToString(", ") { "$it TEXT" }}
//            );
//        """)
//
//        execute("""
//            CREATE TABLE object_locations (
//                id INTEGER PRIMARY KEY AUTOINCREMENT,
//                object_id INTEGER,
//                global_x INTEGER,
//                global_y INTEGER,
//                plane INTEGER,
//                rotation INTEGER,
//                FOREIGN KEY(object_id) REFERENCES objects(object_id)
//            );
//        """)
//
//        execute("""
//            CREATE TABLE tiles (
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
//    }
//
//    // 2️⃣ Insert objects with all flags
//    val insertObj = conn.prepareStatement("""
//        INSERT INTO objects (${baseCols.joinToString(", ")}, ${dynamicCols.joinToString(", ")})
//        VALUES (${List(baseCols.size + dynamicCols.size) { "?" }.joinToString(", ")})
//    """)
//
//    objectDefs.values.forEach { obj ->
//        val id = (obj["id"] as Number).toInt()
//        val name = obj["name"]?.toString() ?: ""
//        val dimX = (obj["dim_x"] as? Number)?.toInt() ?: 1
//        val dimY = (obj["dim_y"] as? Number)?.toInt() ?: 1
//        val actions = gson.toJson(obj["actions"] ?: listOf<String>())
//        val modelsPresent = obj.containsKey("models")
//
//        insertObj.setInt(1, id)
//        insertObj.setString(2, name)
//        insertObj.setInt(3, dimX)
//        insertObj.setInt(4, dimY)
//        insertObj.setString(5, actions)
//        insertObj.setBoolean(6, modelsPresent)
//
//        dynamicCols.forEachIndexed { idx, col ->
//            val raw = obj[col]
//            val value = when (raw) {
//                is List<*> -> gson.toJson(raw)
//                else -> raw?.toString()
//            }
//            insertObj.setString(7 + idx, value)
//        }
//
//        insertObj.execute()
//    }
//    insertObj.close()
//
//    // 3️⃣ Read locations & blocking
//    val tileFlags = mutableMapOf<Triple<Int, Int, Int>, TileFlags>()
//    val minI = 45; val maxI = 55; val minJ = 48; val maxJ = 56
//    val validPlanes = setOf(-1, 0, 1, 2, 3)
//
//    File("extracted/locations").listFiles()?.forEach { file ->
//        val locs = gson.fromJson<List<ObjectLocation>>(file.readText(), object : TypeToken<List<ObjectLocation>>() {}.type)
//        locs.forEach { loc ->
//            if (loc.i !in minI..maxI || loc.j !in minJ..maxJ) return@forEach
//            val globalX = (loc.i shl 6) + loc.x
//            val globalY = (loc.j shl 6) + loc.y
//            val plane = loc.plane
//            if (plane !in validPlanes) return@forEach
//
//            conn.prepareStatement("""
//                INSERT INTO object_locations (object_id, global_x, global_y, plane, rotation)
//                VALUES (?,?,?,?,?)
//            """).use {
//                it.setInt(1, loc.id)
//                it.setInt(2, globalX)
//                it.setInt(3, globalY)
//                it.setInt(4, plane)
//                it.setInt(5, loc.rotation)
//                it.execute()
//            }
//
//            val objDef = objectDefs[loc.id] ?: return@forEach
//            val dimX = (objDef["dim_x"] as? Number)?.toInt() ?: 1
//            val dimY = (objDef["dim_y"] as? Number)?.toInt() ?: 1
//
//            for (dx in 0 until dimX) {
//                for (dy in 0 until dimY) {
//                    val key = Triple(globalX + dx, globalY + dy, plane)
//                    val flags = tileFlags.getOrPut(key) { TileFlags() }
//                    flags.debugReasons += "Object:${loc.id}"
//                    when (loc.rotation) {
//                        1 -> flags.blockN = true
//                        2 -> flags.blockE = true
//                        3 -> flags.blockS = true
//                        0, 4 -> flags.blockW = true
//                    }
//                }
//            }
//        }
//    }
//
//    // 4️⃣ Process tiles
//    File("extracted/tiles").listFiles()?.forEach { file ->
//        val (i, j) = file.nameWithoutExtension.split("_").map { it.toInt() }
//        if (i !in minI..maxI || j !in minJ..maxJ) return@forEach
//
//        val json = gson.fromJson<Map<String, Any>>(file.readText(), object : TypeToken<Map<String, Any>>() {}.type)
//        val dim = json["dim"] as List<*>
//        val tiles = gson.fromJson<List<Tile>>(gson.toJson(json["data"]), object : TypeToken<List<Tile>>() {}.type)
//
//        var idx = 0
//        for (plane in 0 until (dim[0] as Number).toInt()) {
//            if (plane !in validPlanes) continue
//
//            for (x in 0 until (dim[1] as Number).toInt()) {
//                for (y in 0 until (dim[2] as Number).toInt()) {
//                    val tile = tiles[idx++]
//                    val globalX = (i shl 6) + x
//                    val globalY = (j shl 6) + y
//                    val key = Triple(globalX, globalY, plane)
//                    val flags = tileFlags[key]
//
//                    val baseWalkable = tile.settings in setOf(0, 2, 3, 4, 5, 8) || tile.settings == null
//                    val isWalkable = baseWalkable && !(flags?.blockN == true && flags.blockS == true) && !(flags?.blockE == true && flags.blockW == true)
//
//                    conn.prepareStatement("""
//                        INSERT OR REPLACE INTO tiles VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
//                    """).use { stmt ->
//                        stmt.setInt(1, globalX)
//                        stmt.setInt(2, globalY)
//                        stmt.setInt(3, plane)
//                        stmt.setInt(4, tile.height ?: 0)
//                        stmt.setInt(5, tile.overlay_id ?: -1)
//                        stmt.setInt(6, tile.underlay_id ?: -1)
//                        stmt.setInt(7, tile.settings ?: 0)
//                        stmt.setBoolean(8, isWalkable)
//                        stmt.setBoolean(9, flags?.blockN ?: false)
//                        stmt.setBoolean(10, flags?.blockE ?: false)
//                        stmt.setBoolean(11, flags?.blockS ?: false)
//                        stmt.setBoolean(12, flags?.blockW ?: false)
//                        stmt.setString(13, flags?.debugReasons?.joinToString("; ") ?: "")
//                        stmt.execute()
//                    }
//                }
//            }
//        }
//    }
//
//    conn.commit()
//    conn.close()
//    println("✅ Done! Safe with duplicates, all flags stored.")
//}
