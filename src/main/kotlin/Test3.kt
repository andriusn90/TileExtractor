import javafx.application.Application
import javafx.collections.FXCollections
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.canvas.Canvas
import javafx.scene.canvas.GraphicsContext
import javafx.scene.control.*
import javafx.scene.input.MouseButton
import javafx.scene.input.ScrollEvent
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import javafx.scene.paint.Color
import javafx.stage.Stage
import java.sql.DriverManager

data class Tile(
    val x: Int, val y: Int,
    val walkable: Boolean,
    val settings: Int,
    val objectsHere: List<Obj>
)

data class Obj(
    val gx: Int, val gy: Int,
    val dimX: Int, val dimY: Int,
    val flags: Map<String, Any?>
)

data class FlagFilter(
    var enabled: Boolean = false,
    var selectedValue: Any? = null
)

class TileMapViewer : Application() {
    private val conn = DriverManager.getConnection("jdbc:sqlite:rs3_world.db")
    private var tiles = mutableListOf<Tile>()
    private val filters = mutableMapOf<String, FlagFilter>()
    private val flagValues = mutableMapOf<String, MutableSet<Any?>>()
    private var minX = 0; private var maxX = 0; private var minY = 0; private var maxY = 0

    private var zoom = 4.0
    private var offsetX = 0.0
    private var offsetY = 0.0

    private var selectedPlane = 0

    private lateinit var gc: GraphicsContext

    private var dimXEnabled = false
    private var dimXMin = 1
    private var dimXMax = 29

    private var dimYEnabled = false
    private var dimYMin = 1
    private var dimYMax = 29

    override fun start(stage: Stage) {
        println("Loading...")

        // Instead of: val canvas = Canvas(1600.0, 1200.0)
        val canvas = Canvas()
        gc = canvas.graphicsContext2D

        val canvasContainer = BorderPane(canvas)

        // Bind width and height
        canvas.widthProperty().bind(canvasContainer.widthProperty())
        canvas.heightProperty().bind(canvasContainer.heightProperty())

        // Redraw on resize
        canvas.widthProperty().addListener { _, _, _ -> drawTiles() }
        canvas.heightProperty().addListener { _, _, _ -> drawTiles() }

        val planeBox = ComboBox(FXCollections.observableArrayList(0, 1, 2, 3))
        planeBox.selectionModel.select(selectedPlane)
        planeBox.setOnAction {
            selectedPlane = planeBox.value
            loadTiles()
            drawTiles()
        }

        loadTiles()

        // === dim_x ===
        val cbDimX = CheckBox("Use dim_x")

        val dimXMinLabel = Label("Min:")
        val dimXMinField = TextField(dimXMin.toString())
        dimXMinField.prefColumnCount = 2  // Small default width
        dimXMinField.maxWidth = Double.MAX_VALUE

        val dimXMaxLabel = Label("Max:")
        val dimXMaxField = TextField(dimXMax.toString())
        dimXMaxField.prefColumnCount = 2
        dimXMaxField.maxWidth = Double.MAX_VALUE

        HBox.setHgrow(dimXMinField, javafx.scene.layout.Priority.ALWAYS)
        HBox.setHgrow(dimXMaxField, javafx.scene.layout.Priority.ALWAYS)

        val minRow = HBox(2.0, dimXMinLabel, dimXMinField)
        val maxRow = HBox(2.0, dimXMaxLabel, dimXMaxField)

        val dimXBox = VBox(2.0, cbDimX, minRow, maxRow)
        dimXBox.maxWidth = 70.0   // Compact box!
        dimXBox.prefWidth = 70.0
        VBox.setVgrow(dimXBox, javafx.scene.layout.Priority.NEVER)


        // === dim_y ===
        val cbDimY = CheckBox("Use dim_y")

        val dimYMinLabel = Label("Min:")
        val dimYMinField = TextField(dimYMin.toString())
        dimYMinField.prefColumnCount = 2
        dimYMinField.maxWidth = Double.MAX_VALUE

        val dimYMaxLabel = Label("Max:")
        val dimYMaxField = TextField(dimYMax.toString())
        dimYMaxField.prefColumnCount = 2
        dimYMaxField.maxWidth = Double.MAX_VALUE

        HBox.setHgrow(dimYMinField, javafx.scene.layout.Priority.ALWAYS)
        HBox.setHgrow(dimYMaxField, javafx.scene.layout.Priority.ALWAYS)

        val yMinRow = HBox(2.0, dimYMinLabel, dimYMinField)
        val yMaxRow = HBox(2.0, dimYMaxLabel, dimYMaxField)

        val dimYBox = VBox(2.0, cbDimY, yMinRow, yMaxRow)
        dimYBox.maxWidth = 71.0   // Same width
        dimYBox.prefWidth = 71.0
        VBox.setVgrow(dimYBox, javafx.scene.layout.Priority.NEVER)

        val topBox = HBox(10.0, planeBox, dimXBox, dimYBox)
        topBox.padding = Insets(5.0)

        // This VBox holds each flag + its ComboBox
        val flagsContent = VBox(8.0)
        flagsContent.padding = Insets(5.0)

        // === Wrap the WHOLE thing in a ScrollPane ===
        val leftScrollPane = ScrollPane(flagsContent).apply {
            prefWidth = 250.0
            isFitToWidth = true
            isFitToHeight = true
            hbarPolicy = ScrollPane.ScrollBarPolicy.NEVER
            vbarPolicy = ScrollPane.ScrollBarPolicy.AS_NEEDED
            isPannable = true
        }

        // Bind leftScrollPane maxHeight = stage.height - topBox.height
        leftScrollPane.maxHeightProperty().bind(
            stage.heightProperty().subtract(topBox.heightProperty().add(40))
        )

        val rightFlagsContent = VBox(8.0).apply { padding = Insets(5.0) }
        val rightScrollPane = ScrollPane(rightFlagsContent).apply {
            prefWidth = 250.0
            isFitToWidth = true
            isFitToHeight = true
            hbarPolicy = ScrollPane.ScrollBarPolicy.NEVER
            vbarPolicy = ScrollPane.ScrollBarPolicy.AS_NEEDED
            isPannable = true
        }

        // Bind leftScrollPane maxHeight = stage.height - topBox.height
        rightScrollPane.maxHeightProperty().bind(
            stage.heightProperty().subtract(topBox.heightProperty().add(40))
        )

        val leftColumn = HBox(5.0, leftScrollPane)

        val sortedFlags = flagValues.keys.sortedWith(compareBy(
            { flag ->
                when {
                    flag.equals("id", ignoreCase = true) -> 0 // ID always first
                    flag.lowercase().startsWith("unknown") -> 2 // unknowns last
                    else -> 1 // other flags next
                }
            },
            { flag ->
                if (flag.lowercase().startsWith("unknown")) {
                    // extract only the number for numerical sort
                    Regex("""\d+""").find(flag)?.value?.toIntOrNull() ?: Int.MAX_VALUE
                } else {
                    0
                }
            },
            { flag ->
                flag.lowercase()
            }
        ))

        for (flag in sortedFlags) {
            val values = flagValues[flag]!!

            val cb = CheckBox(flag)
            cb.maxWidth = Double.MAX_VALUE

            val combo = ComboBox<Any?>(FXCollections.observableArrayList(values.toList()))
            combo.prefWidth = 120.0
            combo.maxWidth = Double.MAX_VALUE
            combo.selectionModel.selectFirst()

            HBox.setHgrow(combo, javafx.scene.layout.Priority.ALWAYS)

            filters.putIfAbsent(flag, FlagFilter())
            filters[flag]!!.selectedValue = combo.selectionModel.selectedItem

            cb.setOnAction {
                filters[flag]?.enabled = cb.isSelected

                val activeLeftFlags = filters.filterValues { it.enabled }.keys
                filters.forEach { (f, filter) ->
                    if (!activeLeftFlags.contains(f)) {
                        filter.enabled = false
                    }
                }

                updateRightPanel(leftColumn, rightFlagsContent, rightScrollPane)
                drawTiles()
            }

            combo.setOnAction {
                filters[flag]?.selectedValue = combo.selectionModel.selectedItem

                val activeLeftFlags = filters.filterValues { it.enabled }.keys
                filters.forEach { (f, filter) ->
                    if (!activeLeftFlags.contains(f)) {
                        filter.enabled = false
                    }
                }

                updateRightPanel(leftColumn, rightFlagsContent, rightScrollPane)
                drawTiles()
            }

            val flagRow = HBox(5.0, cb, combo)
            flagRow.maxWidth = Double.MAX_VALUE
            flagRow.alignment = Pos.CENTER_LEFT
            cb.padding = Insets(0.0, 0.0, 0.0, 4.0)
            cb.selectedProperty().addListener { _, _, newValue ->
                if (newValue) {
                    flagRow.style = "-fx-background-color: #3399ff;"
                } else {
                    flagRow.style = ""
                }
            }
            flagsContent.children.add(flagRow)
        }

        val root = BorderPane()

        root.top = topBox
        root.left = leftColumn
        root.right = null
        root.center = canvasContainer

        var lastMouseX = 0.0
        var lastMouseY = 0.0

        canvas.setOnMousePressed { e ->
            if (e.button == MouseButton.PRIMARY) {
                lastMouseX = e.x
                lastMouseY = e.y
            }
        }
        canvas.setOnMouseDragged { e ->
            if (e.button == MouseButton.PRIMARY) {
                offsetX += e.x - lastMouseX
                offsetY += e.y - lastMouseY
                lastMouseX = e.x
                lastMouseY = e.y
                drawTiles()
            }
        }
        canvas.addEventFilter(ScrollEvent.SCROLL) { e ->
            val factor = if (e.deltaY > 0) 1.1 else 0.9
            zoom *= factor
            drawTiles()
        }

        stage.scene = Scene(root)
        stage.title = "RuneScape Map Debugger"
        stage.width = 1600.0
        stage.height = 1200.0
        stage.minWidth = 800.0
        stage.minHeight = 600.0
        stage.show()
        drawTiles()
    }

    private fun updateRightPanel(
        leftColumn: HBox,
        rightFlagsContent: VBox,
        rightScrollPane: ScrollPane
    ) {
        val activeLeftFilters = filters.filterValues { it.enabled }
        if (activeLeftFilters.isEmpty()) {
            leftColumn.children.remove(rightScrollPane)

            // ✅ ROW ~384: clear right flags
            val activeLeftFlags = filters.filterValues { it.enabled }.keys
            filters.forEach { (flag, filter) ->
                if (!activeLeftFlags.contains(flag)) {
                    filter.enabled = false
                }
            }
            return
        }

        // 1. Get objects matching ALL left-panel flags (AND)
        val leftMatchingObjs = tiles.flatMap { it.objectsHere }.filter { obj ->
            activeLeftFilters.all { (flag, filter) ->
                obj.flags[flag] == filter.selectedValue
            }
        }

        // 2. Show all flags present on these objects
        val relatedFlags = mutableMapOf<String, MutableSet<Any?>>()
        for (obj in leftMatchingObjs) {
            for ((flag, value) in obj.flags) {
                if (!activeLeftFilters.containsKey(flag)) {
                    relatedFlags.getOrPut(flag) { mutableSetOf() }.add(value)
                }
            }
        }

        // 3. Sort same way
        val sortedRelated = relatedFlags.keys.sortedWith(compareBy(
            { flag ->
                when {
                    flag.equals("id", ignoreCase = true) -> 0
                    flag.lowercase().startsWith("unknown") -> 2
                    else -> 1
                }
            },
            { flag ->
                if (flag.lowercase().startsWith("unknown"))
                    Regex("""\d+""").find(flag)?.value?.toIntOrNull() ?: Int.MAX_VALUE
                else 0
            },
            { it.lowercase() }
        ))

        rightFlagsContent.children.clear()

        for (flag in sortedRelated) {
            val values = relatedFlags[flag]!!.toList()

            val cb = CheckBox(flag)
            cb.maxWidth = Double.MAX_VALUE
            cb.isSelected = filters[flag]?.enabled ?: false

            val combo = ComboBox<Any?>(FXCollections.observableArrayList(values))
            combo.prefWidth = 120.0
            combo.maxWidth = Double.MAX_VALUE
            combo.selectionModel.select(filters[flag]?.selectedValue ?: values.firstOrNull())

            HBox.setHgrow(combo, javafx.scene.layout.Priority.ALWAYS)

            filters.putIfAbsent(flag, FlagFilter())
            filters[flag]!!.selectedValue = combo.selectionModel.selectedItem

            cb.setOnAction {
                filters[flag]?.enabled = cb.isSelected
                drawTiles()
            }

            combo.setOnAction {
                filters[flag]?.selectedValue = combo.selectionModel.selectedItem
                drawTiles()
            }

            val flagRow = HBox(5.0, cb, combo)
            flagRow.maxWidth = Double.MAX_VALUE
            flagRow.alignment = Pos.CENTER_LEFT
            cb.padding = Insets(0.0, 0.0, 0.0, 5.0)
            cb.selectedProperty().addListener { _, _, newValue ->
                if (newValue) {
                    flagRow.style = "-fx-background-color: #3399ff;"
                } else {
                    flagRow.style = ""
                }
            }

            rightFlagsContent.children.add(flagRow)
        }

        if (!leftColumn.children.contains(rightScrollPane)) {
            leftColumn.children.add(rightScrollPane)
        }
    }

    private fun loadTiles() {
        tiles.clear()
        flagValues.clear()

        conn.createStatement().use { stmt ->
            val rs = stmt.executeQuery("""
                SELECT MIN(global_x) AS minX, MAX(global_x) AS maxX,
                       MIN(global_y) AS minY, MAX(global_y) AS maxY
                FROM tiles WHERE plane = $selectedPlane
            """)
            minX = rs.getInt("minX")
            maxX = rs.getInt("maxX")
            minY = rs.getInt("minY")
            maxY = rs.getInt("maxY")
        }

        val objectMap = mutableMapOf<Pair<Int, Int>, MutableList<Obj>>()

        val rsObj = conn.createStatement().executeQuery("""
            SELECT l.global_x, l.global_y, l.plane, o.*
            FROM object_locations l
            JOIN objects o ON o.object_id = l.object_id
            WHERE l.plane = $selectedPlane
        """)
        val meta = rsObj.metaData
        val flagCols = (1..meta.columnCount).map { meta.getColumnName(it) }
            .filter { it !in listOf("object_id", "name", "dim_x", "dim_y", "actions", "models_present",
                "global_x", "global_y", "plane", "rotation") }

        while (rsObj.next()) {
            val gx = rsObj.getInt("global_x")
            val gy = rsObj.getInt("global_y")
            val dimX = rsObj.getObject("dim_x")?.toString()?.toIntOrNull()?.takeIf { it > 0 } ?: 0
            val dimY = rsObj.getObject("dim_y")?.toString()?.toIntOrNull()?.takeIf { it > 0 } ?: 0

            val flags = mutableMapOf<String, Any?>()
            for (col in flagCols) {
                val value = rsObj.getObject(col)
                if (value != null) {
                    flags[col] = value
                    flagValues.getOrPut(col) { mutableSetOf() }.add(value)
                }
            }

            val obj = Obj(gx, gy, dimX, dimY, flags)

            for (dx in 0 until dimX) {
                for (dy in 0 until dimY) {
                    val key = Pair(gx + dx, gy + dy)
                    objectMap.getOrPut(key) { mutableListOf() }.add(obj)
                }
            }
        }
        rsObj.close()

        val rs = conn.createStatement().executeQuery("""
            SELECT * FROM tiles WHERE plane = $selectedPlane
        """)
        while (rs.next()) {
            val gx = rs.getInt("global_x")
            val gy = rs.getInt("global_y")
            val settings = rs.getInt("settings")
            val baseWalkableSettings = setOf(0, 2, 3, 4, 5, 8)
            val isBaseWalkable = settings in baseWalkableSettings
            val isWalkable = rs.getBoolean("is_walkable") || isBaseWalkable

            val objs = objectMap[Pair(gx, gy)] ?: mutableListOf()
            tiles.add(Tile(gx, gy, isWalkable, settings, objs))
        }
        rs.close()
    }

    private fun drawTiles() {
        gc.clearRect(0.0, 0.0, gc.canvas.width, gc.canvas.height)

        for (tile in tiles) {
            val x = (tile.x - minX) * zoom + offsetX
            val y = (maxY - tile.y) * zoom + offsetY

            val baseColor = when {
                tile.walkable && tile.settings == 4 -> Color.GRAY
                tile.walkable -> Color.GREEN
                else -> Color.BLACK
            }
            gc.fill = baseColor
            gc.fillRect(x, y, zoom, zoom)
        }

        for (tile in tiles) {
            for (obj in tile.objectsHere) {
                val flagsActive = filters.any { it.value.enabled }
                val dimFiltersActive = dimXEnabled || dimYEnabled
                val filtersActive = flagsActive || dimFiltersActive

                val dimXOk = if (dimXEnabled) obj.dimX in dimXMin..dimXMax else true
                val dimYOk = if (dimYEnabled) obj.dimY in dimYMin..dimYMax else true

                val flagFilters = filters.filterValues { it.enabled }
                val flagsOk = if (flagFilters.isNotEmpty()) {
                    flagFilters.all { (flag, filter) ->
                        obj.flags[flag] == filter.selectedValue
                    }
                } else true

                val matches = filtersActive && flagsOk && dimXOk && dimYOk

                if (matches) {
                    val drawDimX = if (obj.dimX > 0) obj.dimX else 1
                    val drawDimY = if (obj.dimY > 0) obj.dimY else 1

                    for (dx in 0 until drawDimX) {
                        for (dy in 0 until drawDimY) {
                            val gx = obj.gx + dx
                            val gy = obj.gy + dy
                            val x = (gx - minX) * zoom + offsetX
                            val y = (maxY - gy) * zoom + offsetY
                            gc.fill = Color.PURPLE
                            gc.fillRect(x, y, zoom, zoom)
                        }
                    }
                }
            }
        }
    }
}

fun main() {
    Application.launch(TileMapViewer::class.java)
}