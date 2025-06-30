package com.example.overlay

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.net.toUri
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class FloatingWidgetService : Service() {

    private var windowManager: WindowManager? = null
    private val floatingViews = mutableListOf<View>()
    private val counters = mutableMapOf<View, Int>()
    private val layoutParamsMap = mutableMapOf<View, WindowManager.LayoutParams>()
    private val widgetSizes = mutableMapOf<View, Int>() // Armazena imageSizeInDp
    private val imageUriMap = mutableMapOf<View, String>() // Armazena imageUri como String
    private lateinit var prefs: SharedPreferences

    private var deleteAreaView: View? = null
    private var deleteAreaParams: WindowManager.LayoutParams? = null
    private var isDeleteAreaShowing = false
    private val actualDeleteIconRect = Rect()

    private val handler = Handler(Looper.getMainLooper())
    private val activeLongPressDetectRunnables = mutableMapOf<View, Runnable>()
    private val activeHideControlsRunnables = mutableMapOf<View, Runnable>()

    companion object {
        private const val PREF_NAME = "WidgetPrefs"
        private const val DEFAULT_SIZE_DP = 100
        private const val LONG_PRESS_TIME = 500L
        private const val MOVE_THRESHOLD_DP = 10
        private const val CONTROLS_VISIBILITY_DURATION = 3000L

        const val ACTION_ADJUST_SIZE = "com.example.overlay.ACTION_ADJUST_SIZE"
        const val EXTRA_NEW_SIZE_PX = "com.example.overlay.EXTRA_NEW_SIZE_PX"

        const val ACTION_SAVE_LAYOUT = "com.example.overlay.ACTION_SAVE_LAYOUT"
        const val ACTION_LOAD_LAYOUT = "com.example.overlay.ACTION_LOAD_LAYOUT"
        private const val PREF_KEY_SAVED_LAYOUT = "saved_layout_config"
    }

    private var moveThresholdPx = 0

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        setupDeleteArea()
        val density = resources.displayMetrics.density
        moveThresholdPx = (MOVE_THRESHOLD_DP * density).toInt()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_ADJUST_SIZE -> {
                val newSizePx = intent.getIntExtra(EXTRA_NEW_SIZE_PX, -1)
                if (newSizePx != -1) {
                    val density = resources.displayMetrics.density
                    val imageSizeInDp = (newSizePx / density).toInt()
                    floatingViews.forEach { floatingView ->
                        widgetSizes[floatingView] = imageSizeInDp
                        updateWidgetSize(floatingView, imageSizeInDp)
                    }
                }
            }
            ACTION_SAVE_LAYOUT -> {
                saveCurrentLayout()
            }
            ACTION_LOAD_LAYOUT -> {
                loadSavedLayout()
            }
            else -> { // Ação padrão ou null é para adicionar novo widget
                val imageUriString = intent?.getStringExtra("imageUri")
                imageUriString?.toUri()?.let {
                    addFloatingView(it) // Chama addFloatingView sem posições/tamanhos específicos
                }
            }
        }
        return START_STICKY
    }

    private fun setupDeleteArea() {
        val themedContext = ContextThemeWrapper(this, R.style.Theme_Overlay) // Use seu tema
        deleteAreaView = LayoutInflater.from(themedContext).inflate(R.layout.delete_area_layout, null)
        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        deleteAreaParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_DIM_BEHIND,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            dimAmount = 0.5f
        }
    }

    private fun showDeleteArea() {
        if (deleteAreaView == null || deleteAreaParams == null) return
        if (!isDeleteAreaShowing) {
            try {
                deleteAreaParams!!.flags = (deleteAreaParams!!.flags) and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                windowManager?.addView(deleteAreaView, deleteAreaParams)
                isDeleteAreaShowing = true
                deleteAreaView!!.post {
                    val mainDeleteAreaView = deleteAreaView ?: return@post
                    val barLocation = IntArray(2)
                    mainDeleteAreaView.getLocationOnScreen(barLocation)
                    val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                    val screenWidth: Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        wm.currentWindowMetrics.bounds.width()
                    } else {
                        val displayMetrics = DisplayMetrics()
                        @Suppress("DEPRECATION")
                        wm.defaultDisplay.getMetrics(displayMetrics)
                        displayMetrics.widthPixels
                    }
                    val barWidthMeasureSpec = View.MeasureSpec.makeMeasureSpec(screenWidth, View.MeasureSpec.EXACTLY)
                    val barHeightMeasureSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                    mainDeleteAreaView.measure(barWidthMeasureSpec, barHeightMeasureSpec)
                    val measuredBarWidth = mainDeleteAreaView.measuredWidth
                    val measuredBarHeight = mainDeleteAreaView.measuredHeight
                    val barRect = Rect()
                    if (measuredBarWidth > 0 && measuredBarHeight > 0) {
                        barRect.set(barLocation[0], barLocation[1], barLocation[0] + measuredBarWidth, barLocation[1] + measuredBarHeight)
                    } else {
                        actualDeleteIconRect.setEmpty(); return@post
                    }
                    val iconImageView = mainDeleteAreaView.findViewById<ImageView>(R.id.deleteIconImageView)
                    if (iconImageView != null) {
                        val iconRelativeRect = Rect()
                        iconImageView.getHitRect(iconRelativeRect)
                        actualDeleteIconRect.set(
                            barRect.left + iconRelativeRect.left,
                            barRect.top + iconRelativeRect.top,
                            barRect.left + iconRelativeRect.right,
                            barRect.top + iconRelativeRect.bottom
                        )
                    } else { actualDeleteIconRect.set(barRect) }
                }
            } catch (e: Exception) { isDeleteAreaShowing = false }
        }
    }

    private fun hideDeleteArea() {
        if (isDeleteAreaShowing && deleteAreaView != null) {
            try {
                deleteAreaParams?.flags = (deleteAreaParams?.flags ?: 0) or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                windowManager?.removeView(deleteAreaView)
                isDeleteAreaShowing = false
            } catch (e: Exception) { Log.e("FloatingWidgetService", "Erro ao esconder deleteArea", e) }
        }
    }

    private fun isViewOverDeleteArea(view: View): Boolean {
        if (!isDeleteAreaShowing || deleteAreaView == null || actualDeleteIconRect.isEmpty) return false
        val viewRect = Rect()
        val currentWMParams = layoutParamsMap[view]
        if (currentWMParams != null) {
            val widgetX = currentWMParams.x
            val widgetY = currentWMParams.y
            var currentWidgetWidth = view.width
            val currentWidgetHeight = view.height
            if (currentWidgetWidth <= 0) {
                val imageSizeDp = widgetSizes[view] ?: DEFAULT_SIZE_DP
                currentWidgetWidth = (imageSizeDp * view.resources.displayMetrics.density).toInt()
            }
            if (currentWidgetWidth > 0 && currentWidgetHeight > 0) {
                viewRect.set(widgetX, widgetY, widgetX + currentWidgetWidth, widgetY + currentWidgetHeight)
            } else { viewRect.setEmpty() }
        } else { viewRect.setEmpty() }
        return if (viewRect.isEmpty) false else Rect.intersects(actualDeleteIconRect, viewRect)
    }

    private fun removeAllWidgets() {
        floatingViews.toList().forEach { removeFloatingView(it, performLayoutRefresh = false) }
        floatingViews.clear() // Garante que a lista está vazia
        counters.clear()
        layoutParamsMap.clear()
        widgetSizes.clear()
        imageUriMap.clear()
    }

    private fun removeFloatingView(floatingView: View, performLayoutRefresh: Boolean = true) {
        if (floatingViews.contains(floatingView)) {
            try {
                windowManager?.removeView(floatingView)
                floatingViews.remove(floatingView)
                counters.remove(floatingView)
                layoutParamsMap.remove(floatingView)
                widgetSizes.remove(floatingView)
                imageUriMap.remove(floatingView) // Limpa URI
                prefs.edit().remove("widget_size_${floatingView.hashCode()}").apply()

                activeLongPressDetectRunnables[floatingView]?.let { handler.removeCallbacks(it) }
                activeLongPressDetectRunnables.remove(floatingView)
                activeHideControlsRunnables[floatingView]?.let { handler.removeCallbacks(it) }
                activeHideControlsRunnables.remove(floatingView)

                if (floatingViews.isEmpty()) {
                    stopSelf()
                }
                // else if (performLayoutRefresh) {
                // refreshCurrentLayout() // Removido pois não há mais modos de arranjo
                // }
            } catch (e: Exception) { Log.e("FloatingWidgetService", "Erro ao remover widget",e) }
        }
    }

    private fun addFloatingView(
        imageUri: Uri,
        initialX: Int? = null,
        initialY: Int? = null,
        requestedImageSizeDp: Int? = null
    ) {
        val themedContext = ContextThemeWrapper(this, R.style.Theme_Overlay)
        val floatingView = LayoutInflater.from(themedContext).inflate(R.layout.widget_layout, null)

        imageUriMap[floatingView] = imageUri.toString()
        floatingViews.add(floatingView)

        val actualInitialSizeDp = requestedImageSizeDp
            ?: prefs.getInt("widget_size_${floatingView.hashCode()}", DEFAULT_SIZE_DP)
        widgetSizes[floatingView] = actualInitialSizeDp

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = initialX ?: 100
            y = initialY ?: 100
        }

        try {
            windowManager?.addView(floatingView, params)
        } catch (e: Exception) {
            Log.e("FloatingWidgetService", "Erro ao adicionar view", e)
            floatingViews.remove(floatingView)
            imageUriMap.remove(floatingView)
            widgetSizes.remove(floatingView)
            return
        }
        layoutParamsMap[floatingView] = params

        val imageView = floatingView.findViewById<ImageView>(R.id.floatingImageView)
        val counterTextView = floatingView.findViewById<TextView>(R.id.counterTextView)
        val imageContainer = floatingView.findViewById<FrameLayout>(R.id.imageContainer)

        counters[floatingView] = 0 // Ou carregar estado salvo do contador
        counterTextView.text = "0"

        updateWidgetSize(floatingView, actualInitialSizeDp)

        try {
            contentResolver.openInputStream(imageUri)?.use { stream ->
                imageView.setImageBitmap(BitmapFactory.decodeStream(stream))
            }
        } catch (e: Exception) {
            Log.e("FloatingWidgetService", "Erro ao carregar imagem $imageUri", e)
        }
        imageContainer.setOnTouchListener(createTouchListener(floatingView, params))
        // refreshCurrentLayout() // Removido
    }

    private fun createTouchListener(touchedFloatingView: View, windowWMParams: WindowManager.LayoutParams): View.OnTouchListener {
        var initialWindowX: Int = 0
        var initialWindowY: Int = 0
        var initialTouchRawX: Float = 0.0f
        var initialTouchRawY: Float = 0.0f
        var isMoving: Boolean = false
        var longPressHandledForThisGesture: Boolean = false
        var startTime: Long = 0L

        val counterTextViewRef = touchedFloatingView.findViewById<TextView>(R.id.counterTextView)
        val controlsLayoutRef = touchedFloatingView.findViewById<LinearLayout>(R.id.counterControlsLayout)
        val subtractButton = touchedFloatingView.findViewById<Button>(R.id.subtractButton)
        val resetButton = touchedFloatingView.findViewById<Button>(R.id.resetButton)

        if (subtractButton == null || resetButton == null || controlsLayoutRef == null || counterTextViewRef == null) {
            Log.e("FloatingWidgetService", "CRÍTICO: View de controle NULA para ${touchedFloatingView.hashCode()}")
            return View.OnTouchListener { _, _ -> false }
        }

        subtractButton.setOnClickListener {
            counters[touchedFloatingView] = (counters[touchedFloatingView] ?: 1) - 1
            counterTextViewRef.text = counters[touchedFloatingView].toString()
            controlsLayoutRef.visibility = View.GONE
            activeHideControlsRunnables[touchedFloatingView]?.let { handler.removeCallbacks(it) }
            activeHideControlsRunnables.remove(touchedFloatingView)
        }
        resetButton.setOnClickListener {
            counters[touchedFloatingView] = 0
            counterTextViewRef.text = "0"
            controlsLayoutRef.visibility = View.GONE
            activeHideControlsRunnables[touchedFloatingView]?.let { handler.removeCallbacks(it) }
            activeHideControlsRunnables.remove(touchedFloatingView)
        }

        return View.OnTouchListener { _, event ->
            if (controlsLayoutRef.visibility == View.VISIBLE) {
                if (event.action == MotionEvent.ACTION_DOWN) {
                    val controlsScreenRect = Rect()
                    controlsLayoutRef.getGlobalVisibleRect(controlsScreenRect)
                    if (controlsScreenRect.contains(event.rawX.toInt(), event.rawY.toInt())) {
                        return@OnTouchListener false
                    } else {
                        controlsLayoutRef.visibility = View.GONE
                        activeHideControlsRunnables[touchedFloatingView]?.let { handler.removeCallbacks(it) }
                        activeHideControlsRunnables.remove(touchedFloatingView)
                        return@OnTouchListener true
                    }
                }
                return@OnTouchListener false
            }

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isMoving = false
                    longPressHandledForThisGesture = false
                    startTime = System.currentTimeMillis()
                    initialWindowX = windowWMParams.x
                    initialWindowY = windowWMParams.y
                    initialTouchRawX = event.rawX
                    initialTouchRawY = event.rawY

                    activeLongPressDetectRunnables[touchedFloatingView]?.let { handler.removeCallbacks(it) }
                    activeLongPressDetectRunnables.remove(touchedFloatingView)
                    val currentLongPressDetectRunnable = Runnable {
                        if (!isMoving && !longPressHandledForThisGesture && (System.currentTimeMillis() - startTime) >= LONG_PRESS_TIME) {
                            longPressHandledForThisGesture = true
                            controlsLayoutRef.visibility = View.VISIBLE
                            activeHideControlsRunnables[touchedFloatingView]?.let { handler.removeCallbacks(it) }
                            activeHideControlsRunnables.remove(touchedFloatingView)
                            val hideControlsRunnable = Runnable {
                                controlsLayoutRef.visibility = View.GONE
                                activeHideControlsRunnables.remove(touchedFloatingView)
                            }
                            handler.postDelayed(hideControlsRunnable, CONTROLS_VISIBILITY_DURATION)
                            activeHideControlsRunnables[touchedFloatingView] = hideControlsRunnable
                        }
                    }
                    handler.postDelayed(currentLongPressDetectRunnable, LONG_PRESS_TIME)
                    activeLongPressDetectRunnables[touchedFloatingView] = currentLongPressDetectRunnable
                    return@OnTouchListener true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (longPressHandledForThisGesture) return@OnTouchListener true
                    val dx = event.rawX - initialTouchRawX
                    val dy = event.rawY - initialTouchRawY
                    if (!isMoving && (Math.abs(dx) > moveThresholdPx || Math.abs(dy) > moveThresholdPx)) {
                        isMoving = true
                        activeLongPressDetectRunnables[touchedFloatingView]?.let { handler.removeCallbacks(it) }
                        activeLongPressDetectRunnables.remove(touchedFloatingView)
                        showDeleteArea()
                    }
                    if (isMoving) {
                        windowWMParams.x = initialWindowX + dx.toInt()
                        windowWMParams.y = initialWindowY + dy.toInt()
                        try { windowManager?.updateViewLayout(touchedFloatingView, windowWMParams) } catch (e: Exception) { /* Log */ }
                    }
                    return@OnTouchListener true
                }
                MotionEvent.ACTION_UP -> {
                    activeLongPressDetectRunnables[touchedFloatingView]?.let { handler.removeCallbacks(it) }
                    activeLongPressDetectRunnables.remove(touchedFloatingView)
                    var removed = false
                    if (isMoving) {
                        if (isViewOverDeleteArea(touchedFloatingView)) {
                            removeFloatingView(touchedFloatingView)
                            removed = true
                        }
                    } else if (!longPressHandledForThisGesture) {
                        counters[touchedFloatingView] = (counters[touchedFloatingView] ?: -1) + 1
                        counterTextViewRef.text = counters[touchedFloatingView].toString()
                    }
                    hideDeleteArea()
                    isMoving = false
                    return@OnTouchListener true
                }
                else -> return@OnTouchListener false
            }
        }
    }

    private fun updateWidgetSize(floatingView: View, imageSizeInDp: Int) {
        // ... (código da última versão, com counterSize = imageSizeInPx * 0.45 etc.)
        val density = resources.displayMetrics.density
        val imageSizeInPx = (imageSizeInDp * density).toInt()

        val wmParams = layoutParamsMap[floatingView]
        wmParams?.let {
            it.width = WindowManager.LayoutParams.WRAP_CONTENT
            it.height = WindowManager.LayoutParams.WRAP_CONTENT
            try {
                windowManager?.updateViewLayout(floatingView, it)
            } catch (e: Exception) { Log.e("FloatingWidgetService", "Erro updateWidgetSize no WM", e)}
        }

        prefs.edit().putInt("widget_size_${floatingView.hashCode()}", imageSizeInDp).apply()

        val imageContainer = floatingView.findViewById<FrameLayout>(R.id.imageContainer)
        var icLP = imageContainer.layoutParams as? RelativeLayout.LayoutParams
        if (icLP == null) {
            icLP = RelativeLayout.LayoutParams(imageSizeInPx, imageSizeInPx)
            icLP.addRule(RelativeLayout.CENTER_VERTICAL)
        } else {
            icLP.width = imageSizeInPx
            icLP.height = imageSizeInPx
        }
        imageContainer.layoutParams = icLP

        val counterTextView = floatingView.findViewById<TextView>(R.id.counterTextView)
        val counterSize = (imageSizeInPx * 0.45).toInt()
        val counterTextSizeSp = if (density > 0) (counterSize * 0.5f / density) else 16f

        var counterRLP = counterTextView.layoutParams as? RelativeLayout.LayoutParams
        if (counterRLP == null) {
            counterRLP = RelativeLayout.LayoutParams(counterSize, counterSize)
        } else {
            counterRLP.width = counterSize
            counterRLP.height = counterSize
        }
        counterTextView.layoutParams = counterRLP
        counterTextView.textSize = counterTextSizeSp
        floatingView.requestLayout()
    }

    private fun saveCurrentLayout() {
        Log.d("FloatingWidgetService", "Iniciando saveCurrentLayout. Widgets: ${floatingViews.size}")
        val savedStates = mutableListOf<SavedWidgetState>()
        floatingViews.forEach { view ->
            val params = layoutParamsMap[view]
            val uriString = imageUriMap[view]
            val sizeDp = widgetSizes[view]

            if (params != null && uriString != null && sizeDp != null) {
                savedStates.add(SavedWidgetState(uriString, params.x, params.y, sizeDp))
                Log.d("FloatingWidgetService", "Salvando widget: uri=$uriString, x=${params.x}, y=${params.y}, sizeDp=$sizeDp")
            } else {
                Log.w("FloatingWidgetService", "Dados incompletos para salvar widget: ${view.hashCode()}")
            }
        }

        if (savedStates.isNotEmpty()) {
            val gson = Gson()
            val jsonLayout = gson.toJson(savedStates)
            prefs.edit().putString(PREF_KEY_SAVED_LAYOUT, jsonLayout).apply()
            Log.i("FloatingWidgetService", "Layout salvo com ${savedStates.size} widgets: $jsonLayout")
        } else {
            prefs.edit().remove(PREF_KEY_SAVED_LAYOUT).apply() // Remove se não houver widgets
            Log.i("FloatingWidgetService", "Nenhum widget para salvar. Configuração salva removida.")
        }
    }

    private fun loadSavedLayout() {
        Log.d("FloatingWidgetService", "Iniciando loadSavedLayout.")
        removeAllWidgets() // Limpa os widgets atuais

        val jsonLayout = prefs.getString(PREF_KEY_SAVED_LAYOUT, null)
        if (jsonLayout != null) {
            val gson = Gson()
            val type = object : TypeToken<List<SavedWidgetState>>() {}.type
            try {
                val loadedStates: List<SavedWidgetState> = gson.fromJson(jsonLayout, type)
                Log.i("FloatingWidgetService", "${loadedStates.size} widgets carregados do JSON.")
                if (loadedStates.isEmpty()) {
                    Log.i("FloatingWidgetService", "Layout salvo estava vazio.")
                }
                loadedStates.forEach { state ->
                    Log.d("FloatingWidgetService", "Recriando widget: uri=${state.imageUriString}, x=${state.x}, y=${state.y}, size=${state.imageSizeDp}")
                    addFloatingView(
                        state.imageUriString.toUri(),
                        state.x,
                        state.y,
                        state.imageSizeDp
                    )
                }
            } catch (e: Exception) {
                Log.e("FloatingWidgetService", "Erro ao desserializar layout salvo", e)
            }
        } else {
            Log.i("FloatingWidgetService", "Nenhum layout salvo encontrado para carregar.")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        activeLongPressDetectRunnables.values.forEach { handler.removeCallbacks(it) }
        activeLongPressDetectRunnables.clear()
        activeHideControlsRunnables.values.forEach { handler.removeCallbacks(it) }
        activeHideControlsRunnables.clear()
        floatingViews.toList().forEach { view ->
            try {
                windowManager?.removeView(view)
            } catch (e: Exception) { /* Log */ }
        }
        floatingViews.clear()
        counters.clear()
        layoutParamsMap.clear()
        widgetSizes.clear()
        imageUriMap.clear()
        if (isDeleteAreaShowing && deleteAreaView != null) {
            try {
                windowManager?.removeView(deleteAreaView)
            } catch (e: Exception) { /* Log */ }
            isDeleteAreaShowing = false
            deleteAreaView = null
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}