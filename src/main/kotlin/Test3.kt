import javafx.application.Application
import javafx.application.Platform
import javafx.collections.FXCollections
import javafx.collections.transformation.FilteredList
import javafx.concurrent.Task
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
import java.io.File
import java.sql.DriverManager
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

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
    var enabledLeft: Boolean = false,
    var enabledRight: Boolean = false,
    var selectedValue: Any? = null
)

class TileMapViewer : Application() {
    private val conn = DriverManager.getConnection("jdbc:sqlite:rs3_world.db")
    private var tiles = mutableListOf<Tile>()
    private val allObjects = mutableSetOf<Obj>()
    private var precomputedFlagToObjects = mutableMapOf<String, MutableMap<Any?, MutableSet<Obj>>>()
    private lateinit var precomputedRelatedFlags: Map<String, Map<String, Set<Any?>>>
    private val rightFlagRows = mutableMapOf<String, HBox>()
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

    private var coordsEnabled = true // Start with coordinates filter enabled
    private var coordXMin = 2900
    private var coordXMax = 3500
    private var coordYMin = 3100
    private var coordYMax = 3600

    // --- Optimization: Debounce redraws ---
    private var redrawRequested = false
    private fun requestRedraw() {
        if (!redrawRequested) {
            redrawRequested = true
            object : javafx.animation.AnimationTimer() {
                override fun handle(now: Long) {
                    drawTiles()
                    redrawRequested = false
                    stop()
                }
            }.start()
        }
    }

    override fun start(stage: Stage) {
        println("Starting application...")
        System.out.flush()

        // Create indexes for all flag columns for fast filtering
        createIndexesForFlagColumns()

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

        println("Loading initial data...")
        System.out.flush()
        loadTiles()

        println("Building UI...")
        System.out.flush()

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

        // Make dimX checkbox work
        cbDimX.selectedProperty().addListener { _, _, newValue ->
            dimXEnabled = newValue
            dimXMin = dimXMinField.text.toIntOrNull() ?: 1
            dimXMax = dimXMaxField.text.toIntOrNull() ?: 29
            drawTiles()
        }

        // Also update on text change
        dimXMinField.textProperty().addListener { _, _, _ ->
            dimXMin = dimXMinField.text.toIntOrNull() ?: 1
            drawTiles()
        }

        dimXMaxField.textProperty().addListener { _, _, _ ->
            dimXMax = dimXMaxField.text.toIntOrNull() ?: 29
            drawTiles()
        }

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

        cbDimY.selectedProperty().addListener { _, _, newValue ->
            dimYEnabled = newValue
            dimYMin = dimYMinField.text.toIntOrNull() ?: 1
            dimYMax = dimYMaxField.text.toIntOrNull() ?: 29
            drawTiles()
        }

        dimYMinField.textProperty().addListener { _, _, _ ->
            dimYMin = dimYMinField.text.toIntOrNull() ?: 1
            drawTiles()
        }

        dimYMaxField.textProperty().addListener { _, _, _ ->
            dimYMax = dimYMaxField.text.toIntOrNull() ?: 29
            drawTiles()
        }

        // === coordinates ===
        val cbCoords = CheckBox("Use coordinates")
        cbCoords.isSelected = true // Start checked
        val coordXMinLabel = Label("Xmin")
        val coordXMinField = TextField(coordXMin.toString())
        coordXMinField.prefColumnCount = 4
        coordXMinField.maxWidth = Double.MAX_VALUE
        val coordXMaxLabel = Label("Xmax")
        val coordXMaxField = TextField(coordXMax.toString())
        coordXMaxField.prefColumnCount = 4
        coordXMaxField.maxWidth = Double.MAX_VALUE
        val coordYMinLabel = Label("Ymin")
        val coordYMinField = TextField(coordYMin.toString())
        coordYMinField.prefColumnCount = 4
        coordYMinField.maxWidth = Double.MAX_VALUE
        val coordYMaxLabel = Label("Ymax")
        val coordYMaxField = TextField(coordYMax.toString())
        coordYMaxField.prefColumnCount = 4
        coordYMaxField.maxWidth = Double.MAX_VALUE
        val xRow = HBox(2.0, coordXMinLabel, coordXMinField, coordXMaxLabel, coordXMaxField)
        val yRow = HBox(2.0, coordYMinLabel, coordYMinField, coordYMaxLabel, coordYMaxField)
        val coordsBox = VBox(2.0, cbCoords, xRow, yRow)
        coordsBox.maxWidth = 200.0
        coordsBox.prefWidth = 200.0
        VBox.setVgrow(coordsBox, javafx.scene.layout.Priority.NEVER)

        cbCoords.selectedProperty().addListener { _, _, newValue ->
            coordsEnabled = newValue
            coordXMin = coordXMinField.text.toIntOrNull() ?: 2900
            coordXMax = coordXMaxField.text.toIntOrNull() ?: 3500
            coordYMin = coordYMinField.text.toIntOrNull() ?: 3100
            coordYMax = coordYMaxField.text.toIntOrNull() ?: 3600
            if (newValue) {
                loadTiles() // Reload tiles when enabling coordinates
            }
            drawTiles()
        }
        coordXMinField.textProperty().addListener { _, _, _ ->
            coordXMin = coordXMinField.text.toIntOrNull() ?: 2900
            if (cbCoords.isSelected) {
                loadTiles() // Reload tiles with new coordinates only if checked
            }
            drawTiles()
        }
        coordXMaxField.textProperty().addListener { _, _, _ ->
            coordXMax = coordXMaxField.text.toIntOrNull() ?: 3500
            if (cbCoords.isSelected) {
                loadTiles()
            }
            drawTiles()
        }
        coordYMinField.textProperty().addListener { _, _, _ ->
            coordYMin = coordYMinField.text.toIntOrNull() ?: 3100
            if (cbCoords.isSelected) {
                loadTiles()
            }
            drawTiles()
        }
        coordYMaxField.textProperty().addListener { _, _, _ ->
            coordYMax = coordYMaxField.text.toIntOrNull() ?: 3600
            if (cbCoords.isSelected) {
                loadTiles()
            }
            drawTiles()
        }

        val topBox = HBox(10.0, planeBox, dimXBox, dimYBox, coordsBox)
        topBox.padding = Insets(5.0)

        // This VBox holds each flag + its ComboBox
        val flagsContent = VBox(8.0)
        flagsContent.padding = Insets(5.0)

        // Add title above flagsContent (left pane)
        val leftTitle = Label("ALL OBJECTS FLAGS")
        leftTitle.style = "-fx-font-size: 16px; -fx-font-weight: bold;"
        val leftVBox = VBox(0.0, leftTitle, flagsContent)
        leftVBox.padding = Insets(0.0, 0.0, 0.0, 0.0)

        // === Wrap the WHOLE thing in a ScrollPane ===
        val leftScrollPane = ScrollPane(leftVBox).apply {
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
        // Add title above rightFlagsContent (right pane)
        val rightTitle = Label("FILTERED OBJECTS FLAGS")
        rightTitle.style = "-fx-font-size: 16px; -fx-font-weight: bold;"
        val rightVBox = VBox(0.0, rightTitle, rightFlagsContent)
        rightVBox.padding = Insets(0.0, 0.0, 0.0, 0.0)
        val rightScrollPane = ScrollPane(rightVBox).apply {
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
            val rawValues = flagValues[flag]!!.toList().sortedBy { it.toString() }
            val hasMultipleValues = rawValues.size > 1
            val values = if (hasMultipleValues) mutableListOf<Any?>("ALL VALUES") else mutableListOf()
            values.addAll(rawValues)

            // Left pane row
            val leftRow = createFlagRow(flag, values, isRight = false, leftColumn, rightFlagsContent, rightScrollPane)
            flagsContent.children.add(leftRow)

            // Right pane row (hidden by default, not managed)
            val rightRow = createFlagRow(flag, values, isRight = true, leftColumn, rightFlagsContent, rightScrollPane)
            rightRow.isVisible = false
            rightRow.isManaged = false
            rightFlagsContent.children.add(rightRow)
        }

        val root = BorderPane()

        root.top = topBox
        root.left = leftColumn
        root.right = rightScrollPane
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
                requestRedraw()
            }
        }
        canvas.addEventFilter(ScrollEvent.SCROLL) { e ->
            val oldZoom = zoom
            val factor = if (e.deltaY > 0) 1.1 else 0.9
            zoom *= factor
            // --- Zoom at mouse cursor ---
            val mouseX = e.x
            val mouseY = e.y
            offsetX = (offsetX - mouseX) * (zoom / oldZoom) + mouseX
            offsetY = (offsetY - mouseY) * (zoom / oldZoom) + mouseY
            requestRedraw()
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

    private fun createFlagRow(
        flag: String,
        values: List<Any?>,
        isRight: Boolean,
        leftColumn: HBox,
        rightFlagsContent: VBox,
        rightScrollPane: ScrollPane
    ): HBox {
        val cb = CheckBox(flag)
        cb.maxWidth = Double.MAX_VALUE

        val hasMultiple = values.size > 1
        val default = if (values.contains("ALL VALUES")) "ALL VALUES" else values.firstOrNull()
        filters.getOrPut(flag) { FlagFilter() }.selectedValue = if (default == "ALL VALUES") null else default

        val combo = createSearchableComboBox(values, default) { newValue ->
            if (newValue == "ALL VALUES") {
                filters[flag]?.selectedValue = null
            } else {
                filters[flag]?.selectedValue = newValue
            }

            if (isRight) {
                drawTiles()
            } else {
                updateRightPanel(leftColumn, rightFlagsContent, rightScrollPane)
                drawTiles()
            }
        }

        cb.setOnAction {
            if (isRight) {
                filters[flag]?.enabledRight = cb.isSelected
                drawTiles()
            } else {
                filters[flag]?.enabledLeft = cb.isSelected
                updateRightPanel(leftColumn, rightFlagsContent, rightScrollPane)
                drawTiles()
            }
        }

        val flagRow = HBox(5.0, cb, combo)
        flagRow.id = flag  // for reuse
        flagRow.maxWidth = Double.MAX_VALUE
        flagRow.alignment = Pos.CENTER_LEFT
        cb.padding = Insets(0.0, 0.0, 0.0, if (isRight) 5.0 else 4.0)

        cb.selectedProperty().addListener { _, _, newValue ->
            flagRow.style = if (newValue) "-fx-background-color: #3399ff;" else ""
        }

        if (isRight) rightFlagRows[flag] = flagRow
        return flagRow
    }


    private fun updateFlagRow(row: HBox, flag: String, values: List<Any?>, isRight: Boolean) {
        val cb = row.children[0] as CheckBox
        val combo = row.children[1] as ComboBox<Any?>

        cb.isSelected = if (isRight) filters[flag]?.enabledRight == true else filters[flag]?.enabledLeft == true

        val originalItems = FXCollections.observableArrayList(values)
        val filteredItems = FilteredList(originalItems) { true }
        combo.items = filteredItems

        // Use the same guard style on the ComboBox
        combo.properties["isUpdating"] = false

        combo.editor.textProperty().addListener { _, _, newValue ->
            if (combo.properties["isUpdating"] == true) return@addListener
            filteredItems.setPredicate {
                it?.toString()?.contains(newValue, ignoreCase = true) ?: false
            }
            if (!combo.isShowing) combo.show()
        }

        val validValue = filters[flag]?.selectedValue?.takeIf { it in values }
            ?: if (values.contains("ALL VALUES")) "ALL VALUES" else values.firstOrNull()
        filters[flag]?.selectedValue = if (validValue == "ALL VALUES") null else validValue

        combo.properties["isUpdating"] = true
        combo.selectionModel.select(validValue)
        combo.editor.text = validValue?.toString() ?: ""
        combo.properties["isUpdating"] = false
    }

    // Helper to encode left filter state as a string key
    private fun leftFilterKey(filters: Map<String, FlagFilter>): String {
        return filters.filter { it.value.enabledLeft }
            .map { (flag, filter) ->
                val v = filter.selectedValue?.toString() ?: "ALL"
                "$flag:$v"
            }.sorted().joinToString("|")
    }

    // Helper to build SQL WHERE clause from active filters
    private fun buildWhereClause(filters: Map<String, FlagFilter>): Pair<String, List<Any?>> {
        val clauses = mutableListOf<String>()
        val params = mutableListOf<Any?>()
        for ((flag, filter) in filters) {
            if (filter.enabledLeft && filter.selectedValue != null) {
                clauses.add("$flag = ?")
                params.add(filter.selectedValue)
            } else if (filter.enabledLeft) {
                clauses.add("$flag IS NOT NULL")
            }
        }
        val where = if (clauses.isNotEmpty()) "WHERE " + clauses.joinToString(" AND ") else ""
        return Pair(where, params)
    }

    private fun updateRightPanel(
        leftColumn: HBox,
        rightFlagsContent: VBox,
        rightScrollPane: ScrollPane
    ) {
        val activeLeftFilters = filters.filterValues { it.enabledLeft }
        filters.forEach { (_, filter) -> filter.enabledRight = false }
        // Find objects matching all left filters
        val matchingObjects = allObjects.filter { obj ->
            activeLeftFilters.all { (flag, filter) ->
                filter.selectedValue?.let { obj.flags[flag] == it } ?: obj.flags.containsKey(flag)
            }
        }
        // Show all flags present in matching objects (union), but exclude flags checked on the left pane
        val leftCheckedFlags = activeLeftFilters.keys
        val commonFlags = if (matchingObjects.isNotEmpty()) {
            matchingObjects.flatMap { it.flags.keys }.toSet().minus(leftCheckedFlags)
        } else emptySet<String>()
        Platform.runLater {
            rightFlagsContent.isVisible = true
            // Hide all right flags if no left filters are enabled
            if (activeLeftFilters.isEmpty()) {
                rightFlagsContent.children.forEach { it.isVisible = false; it.isManaged = false }
            } else {
                rightFlagsContent.children.forEach { child ->
                    val flagRow = child as? HBox
                    val flagName = flagRow?.id
                    val shouldShow = flagName != null && commonFlags.contains(flagName)
                    flagRow?.isVisible = shouldShow
                    flagRow?.isManaged = shouldShow
                }
            }
            // Reorder: visible rows at top, hidden at bottom
            val visibleRows = rightFlagRows.values.filter { it.isVisible }
            val hiddenRows = rightFlagRows.values.filter { !it.isVisible }
            rightFlagsContent.children.setAll(visibleRows + hiddenRows)
        }
    }

    fun createSearchableComboBox(
        values: List<Any?>,
        selectedValue: Any?,
        onSelected: (Any?) -> Unit
    ): ComboBox<Any?> {
        val combo = ComboBox<Any?>()
        combo.isEditable = true

        val originalItems = FXCollections.observableArrayList(values)
        val filteredItems = FilteredList(originalItems) { true }
        combo.items = filteredItems

        // Use ComboBox property as a guard flag
        combo.properties["isUpdating"] = false

        // When user types, filter the items
        combo.editor.textProperty().addListener { _, _, newText ->
            if (combo.properties["isUpdating"] == true) return@addListener

            filteredItems.setPredicate {
                it?.toString()?.contains(newText, ignoreCase = true) ?: false
            }
            if (!combo.isShowing) combo.show()
        }

        // When user picks a value: safely update editor and call callback
        combo.valueProperty().addListener { _, _, newValue ->
            combo.properties["isUpdating"] = true
            combo.editor.text = newValue?.toString() ?: ""
            combo.properties["isUpdating"] = false

            onSelected(newValue)
        }

        // Set initial value if valid
        if (selectedValue in values) {
            combo.selectionModel.select(selectedValue)
        }

        combo.prefWidth = 120.0
        combo.maxWidth = Double.MAX_VALUE

        return combo
    }

    private fun loadTiles() {
        println("Loading tiles and objects...")
        System.out.flush()

        tiles.clear()
        flagValues.clear()

        println("Loading tile bounds...")
        System.out.flush()

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

        println("Loading objects from database...")
        System.out.flush()

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

        println("Processing objects and flags...")
        System.out.flush()

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
        allObjects.clear()
        tiles.forEach { tile -> allObjects.addAll(tile.objectsHere) }

        println("Building flag indexes...")
        System.out.flush()

        val flagToObjects = mutableMapOf<String, MutableMap<Any?, MutableSet<Obj>>>()

        allObjects.forEach { obj ->
            for ((flag, value) in obj.flags) {
                val valueMap = flagToObjects.getOrPut(flag) { mutableMapOf() }
                valueMap.getOrPut(value) { mutableSetOf() }.add(obj)
            }
        }

        precomputedFlagToObjects = flagToObjects

        val relatedFlags = mutableMapOf<String, MutableMap<String, MutableSet<Any?>>>()

        for (obj in allObjects) {
            val flags = obj.flags.keys
            for (flag in flags) {
                val map = relatedFlags.getOrPut(flag) { mutableMapOf() }
                for (other in flags) {
                    if (flag == other) continue
                    val otherValues = map.getOrPut(other) { mutableSetOf() }
                    otherValues.add(obj.flags[other])
                }
            }
        }

        precomputedRelatedFlags = relatedFlags

        println("Finished loading tiles and objects")
        System.out.flush()
    }

    private var mapImage: javafx.scene.image.WritableImage? = null
    private var lastImageParams: Triple<Double, Double, Double>? = null // zoom, offsetX, offsetY

    private var mapBuffer: javafx.scene.image.WritableImage? = null
    private var bufferWidth = 0
    private var bufferHeight = 0
    private var bufferValid = false

    private fun renderMapBuffer() {
        bufferWidth = (maxX - minX + 1)
        bufferHeight = (maxY - minY + 1)
        if (bufferWidth <= 0 || bufferHeight <= 0) return
        val image = javafx.scene.image.WritableImage(bufferWidth, bufferHeight)
        val pixelWriter = image.pixelWriter
        // Paint tiles in a single loop
        tiles.forEach { tile ->
            val x = tile.x - minX
            val y = maxY - tile.y
            if (x !in 0 until bufferWidth || y !in 0 until bufferHeight) return@forEach
            val baseColor = when {
                tile.walkable && tile.settings == 4 -> Color.GRAY
                tile.walkable -> Color.GREEN
                else -> Color.BLACK
            }
            pixelWriter.setColor(x, y, baseColor)
        }
        // Paint objects efficiently
        for (tile in tiles) {
            for (obj in tile.objectsHere) {
                val dimFiltersActive = dimXEnabled || dimYEnabled
                val leftFilters = filters.filterValues { it.enabledLeft }
                val rightFilters = filters.filterValues { it.enabledRight }
                val leftActive = leftFilters.isNotEmpty()
                val rightActive = rightFilters.isNotEmpty()
                if (!leftActive && !rightActive && !dimFiltersActive) continue
                val dimXOk = !dimXEnabled || (obj.dimX in dimXMin..dimXMax)
                val dimYOk = !dimYEnabled || (obj.dimY in dimYMin..dimYMax)
                val leftOk = !leftActive || leftFilters.all { (flag, filter) ->
                    filter.selectedValue?.let { obj.flags[flag] == it } ?: obj.flags.containsKey(flag)
                }
                val rightOk = !rightActive || rightFilters.all { (flag, filter) ->
                    filter.selectedValue?.let { obj.flags[flag] == it } ?: obj.flags.containsKey(flag)
                }
                if (leftOk && rightOk && dimXOk && dimYOk) {
                    val drawDimX = obj.dimX.coerceAtLeast(1)
                    val drawDimY = obj.dimY.coerceAtLeast(1)
                    for (dx in 0 until drawDimX) {
                        for (dy in 0 until drawDimY) {
                            val gx = obj.gx + dx - minX
                            val gy = maxY - (obj.gy + dy)
                            if (gx in 0 until bufferWidth && gy in 0 until bufferHeight) {
                                pixelWriter.setColor(gx, gy, Color.PURPLE)
                            }
                        }
                    }
                }
            }
        }
        mapBuffer = image
        bufferValid = true
    }

    private fun renderMapImage() {
        val canvasW = gc.canvas.width.toInt()
        val canvasH = gc.canvas.height.toInt()
        if (canvasW <= 0 || canvasH <= 0) return
        val image = javafx.scene.image.WritableImage(canvasW, canvasH)
        val pixelWriter = image.pixelWriter
        val minTileX = ((-offsetX) / zoom + minX).toInt().coerceAtLeast(minX)
        val maxTileX = ((canvasW - offsetX) / zoom + minX).toInt().coerceAtMost(maxX)
        val minTileY = (maxY - (canvasH - offsetY) / zoom).toInt().coerceAtLeast(minY)
        val maxTileY = (maxY - (-offsetY) / zoom).toInt().coerceAtMost(maxY)
        for (tile in tiles) {
            if (tile.x < minTileX || tile.x > maxTileX || tile.y < minTileY || tile.y > maxTileY) continue
            val x = ((tile.x - minX) * zoom + offsetX).toInt()
            val y = ((maxY - tile.y) * zoom + offsetY).toInt()
            val baseColor = when {
                tile.walkable && tile.settings == 4 -> Color.GRAY
                tile.walkable -> Color.GREEN
                else -> Color.BLACK
            }
            for (dx in 0 until zoom.toInt().coerceAtLeast(1)) {
                for (dy in 0 until zoom.toInt().coerceAtLeast(1)) {
                    val px = x + dx
                    val py = y + dy
                    if (px in 0 until canvasW && py in 0 until canvasH) {
                        pixelWriter.setColor(px, py, baseColor)
                    }
                }
            }
        }
        for (tile in tiles) {
            for (obj in tile.objectsHere) {
                val dimFiltersActive = dimXEnabled || dimYEnabled
                val leftFilters = filters.filterValues { it.enabledLeft }
                val rightFilters = filters.filterValues { it.enabledRight }
                val leftActive = leftFilters.isNotEmpty()
                val rightActive = rightFilters.isNotEmpty()
                if (!leftActive && !rightActive && !dimFiltersActive) continue
                val dimXOk = if (dimXEnabled) obj.dimX in dimXMin..dimXMax else true
                val dimYOk = if (dimYEnabled) obj.dimY in dimYMin..dimYMax else true
                val leftOk = if (leftActive) leftFilters.all { (flag, filter) ->
                    if (filter.selectedValue == null) obj.flags.containsKey(flag)
                    else obj.flags[flag] == filter.selectedValue
                } else true
                val rightOk = if (rightActive) rightFilters.all { (flag, filter) ->
                    if (filter.selectedValue == null) obj.flags.containsKey(flag)
                    else obj.flags[flag] == filter.selectedValue
                } else true
                val matches = leftOk && rightOk && dimXOk && dimYOk
                if (matches) {
                    val drawDimX = if (obj.dimX > 0) obj.dimX else 1
                    val drawDimY = if (obj.dimY > 0) obj.dimY else 1
                    for (dx in 0 until drawDimX) {
                        for (dy in 0 until drawDimY) {
                            val gx = obj.gx + dx
                            val gy = obj.gy + dy
                            val x = ((gx - minX) * zoom + offsetX).toInt()
                            val y = ((maxY - gy) * zoom + offsetY).toInt()
                            for (px in x until x + zoom.toInt().coerceAtLeast(1)) {
                                for (py in y until y + zoom.toInt().coerceAtLeast(1)) {
                                    if (px in 0 until canvasW && py in 0 until canvasH) {
                                        pixelWriter.setColor(px, py, Color.PURPLE)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        mapImage = image
        lastImageParams = Triple(zoom, offsetX, offsetY)
    }

    private fun clampOffsets(canvasW: Int, canvasH: Int) {
        val mapPixelWidth = (maxX - minX + 1) * zoom
        val mapPixelHeight = (maxY - minY + 1) * zoom
        // If map is smaller than canvas, center it and prevent panning
        if (mapPixelWidth <= canvasW) {
            offsetX = ((canvasW - mapPixelWidth) / 2.0).coerceAtLeast(0.0)
        } else {
            offsetX = offsetX.coerceIn(canvasW - mapPixelWidth, 0.0)
        }
        if (mapPixelHeight <= canvasH) {
            offsetY = ((canvasH - mapPixelHeight) / 2.0).coerceAtLeast(0.0)
        } else {
            offsetY = offsetY.coerceIn(canvasH - mapPixelHeight, 0.0)
        }
    }

    private fun drawTiles() {
        gc.clearRect(0.0, 0.0, gc.canvas.width, gc.canvas.height)
        val canvasW = gc.canvas.width.toInt()
        val canvasH = gc.canvas.height.toInt()
        if (canvasW <= 0 || canvasH <= 0) return
        clampOffsets(canvasW, canvasH)
        val minTileX = ((-offsetX) / zoom + minX).toInt().coerceAtLeast(minX)
        val maxTileX = ((canvasW - offsetX) / zoom + minX).toInt().coerceAtMost(maxX)
        val minTileY = (maxY - (canvasH - offsetY) / zoom).toInt().coerceAtLeast(minY)
        val maxTileY = (maxY - (-offsetY) / zoom).toInt().coerceAtMost(maxY)
        for (tile in tiles) {
            if (tile.x < minTileX || tile.x > maxTileX || tile.y < minTileY || tile.y > maxTileY) continue
            val x = ((tile.x - minX) * zoom + offsetX)
            val y = ((maxY - tile.y) * zoom + offsetY)
            val baseColor = when {
                tile.walkable && tile.settings == 4 -> Color.GRAY
                tile.walkable -> Color.GREEN
                else -> Color.BLACK
            }
            gc.fill = baseColor
            gc.fillRect(x, y, zoom, zoom)
        }
        for (tile in tiles) {
            // Filter tiles by coordinates if enabled
            if (coordsEnabled && (tile.x < coordXMin || tile.x > coordXMax || tile.y < coordYMin || tile.y > coordYMax)) continue
            for (obj in tile.objectsHere) {
                val dimFiltersActive = dimXEnabled || dimYEnabled
                val leftFilters = filters.filterValues { it.enabledLeft }
                val rightFilters = filters.filterValues { it.enabledRight }
                val leftActive = leftFilters.isNotEmpty()
                val rightActive = rightFilters.isNotEmpty()
                if (!leftActive && !rightActive && !dimFiltersActive) continue
                val dimXOk = if (dimXEnabled) obj.dimX in dimXMin..dimXMax else true
                val dimYOk = if (dimYEnabled) obj.dimY in dimYMin..dimYMax else true
                val leftOk = if (leftActive) leftFilters.all { (flag, filter) ->
                    if (filter.selectedValue == null) obj.flags.containsKey(flag)
                    else obj.flags[flag] == filter.selectedValue
                } else true
                val rightOk = if (rightActive) rightFilters.all { (flag, filter) ->
                    if (filter.selectedValue == null) obj.flags.containsKey(flag)
                    else obj.flags[flag] == filter.selectedValue
                } else true
                val matches = leftOk && rightOk && dimXOk && dimYOk
                if (matches) {
                    val drawDimX = if (obj.dimX > 0) obj.dimX else 1
                    val drawDimY = if (obj.dimY > 0) obj.dimY else 1
                    for (dx in 0 until drawDimX) {
                        for (dy in 0 until drawDimY) {
                            val gx = obj.gx + dx
                            val gy = obj.gy + dy
                            val x = ((gx - minX) * zoom + offsetX)
                            val y = ((maxY - gy) * zoom + offsetY)
                            gc.fill = Color.PURPLE
                            gc.fillRect(x, y, zoom, zoom)
                        }
                    }
                }
            }
        }
        // Draw blue boundary lines if coordinates filter is enabled
        if (coordsEnabled) {
            gc.stroke = Color.BLUE
            gc.lineWidth = 2.0
            // Vertical lines for min/max X
            val minXLine = ((coordXMin - minX) * zoom + offsetX)
            val maxXLine = ((coordXMax - minX + 1) * zoom + offsetX)
            gc.strokeLine(minXLine, 0.0, minXLine, canvasH.toDouble())
            gc.strokeLine(maxXLine, 0.0, maxXLine, canvasH.toDouble())
            // Horizontal lines for min/max Y
            val minYLine = ((maxY - coordYMin + 1) * zoom + offsetY)
            val maxYLine = ((maxY - coordYMax) * zoom + offsetY)
            gc.strokeLine(0.0, minYLine, canvasW.toDouble(), minYLine)
            gc.strokeLine(0.0, maxYLine, canvasW.toDouble(), maxYLine)
        }
    }

    // Invalidate buffer when filters or tiles change
    private fun invalidateBuffer() {
        bufferValid = false
    }


    private fun createIndexesForFlagColumns() {
        try {
            val dbMeta = conn.metaData
            val rsCols = dbMeta.getColumns(null, null, "objects", null)
            val flagCols = mutableListOf<String>()
            while (rsCols.next()) {
                val colName = rsCols.getString("COLUMN_NAME")
                if (colName != "id") { // skip id, usually already indexed
                    flagCols.add(colName)
                }
            }
            rsCols.close()
            for (flag in flagCols) {
                val idxName = "idx_objects_${flag}"
                val sql = "CREATE INDEX IF NOT EXISTS $idxName ON objects($flag)"
                try {
                    conn.createStatement().use { it.execute(sql) }
                } catch (e: Exception) {
                    println("Index creation failed for $flag: ${e.message}")
                }
            }
        } catch (e: Exception) {
            println("Error creating indexes: ${e.message}")
        }
    }
}

fun main() {
    Application.launch(TileMapViewer::class.java)
}
