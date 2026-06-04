package app.wristotp.watch

import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import app.wristotp.core.http.UploadHttpServer
import app.wristotp.core.http.UploadPageText
import app.wristotp.core.http.UploadRequest
import app.wristotp.core.http.UploadResponse
import app.wristotp.core.importer.BackupImporter
import app.wristotp.core.model.AppState
import app.wristotp.core.model.Authenticator
import app.wristotp.core.model.Category
import app.wristotp.core.model.ImportFailure
import app.wristotp.core.model.OtpType
import app.wristotp.core.otp.OtpGenerator
import app.wristotp.core.storage.FileDataStore
import java.net.NetworkInterface
import java.util.Collections
import kotlin.concurrent.thread
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import android.window.OnBackInvokedDispatcher

private const val REORDER_PLACEMENT_ANIMATION_MS = 360

class MainActivity : ComponentActivity() {
    private lateinit var dataStore: FileDataStore
    private val importer = BackupImporter()
    private var uploadServer: UploadHttpServer? = null
    private var handleBack: (() -> Unit)? = null
    private var navigateToList: (() -> Unit)? = null
    private var screenForBack: Screen = Screen.List

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dataStore = FileDataStore(filesDir)

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    handleBackPress()
                }
            },
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
            ) {
                handleBackPress()
            }
        }

        setContentView(ComposeView(this).apply {
            setContent {
            var appState by remember { mutableStateOf(dataStore.load()) }
            var screen by remember { mutableStateOf<Screen>(Screen.List) }
            var selectedCategoryId by remember { mutableStateOf<String?>(null) }
            var statusMessage by remember { mutableStateOf(appState.dataError) }

            screenForBack = screen
            navigateToList = { screen = Screen.List }
            handleBack = {
                when (screenForBack) {
                    Screen.List -> finish()
                    Screen.Import -> {
                        stopServer()
                        navigateToList?.invoke()
                    }
                    is Screen.Detail -> navigateToList?.invoke()
                }
            }
            DisposableEffect(Unit) {
                onDispose {
                    handleBack = null
                    navigateToList = null
                }
            }

            WristOtpTheme {
                when (val current = screen) {
                    Screen.List -> MainScreen(
                        appState = appState,
                        selectedCategoryId = selectedCategoryId,
                        statusMessage = statusMessage,
                        onSelectCategory = { selectedCategoryId = it },
                        onImport = {
                            screen = Screen.Import
                            statusMessage = null
                        },
                        onOpenAuthenticator = { screen = Screen.Detail(it) },
                        onReorderAuthenticators = { reordered ->
                            appState = appState.copy(authenticators = reordered)
                            thread(name = "wristotp-save-order", isDaemon = true) {
                                runCatching {
                                    dataStore.saveAuthenticators(reordered)
                                }.onFailure { e ->
                                    runOnUiThread {
                                        statusMessage = e.message ?: "Could not save order"
                                    }
                                }
                            }
                        },
                    )

                    Screen.Import -> ImportScreen(
                        address = "http://${findLanIpv4()}:8765/",
                        statusMessage = statusMessage,
                        onStart = {
                            startServer(
                                onStateChanged = { importedState, message ->
                                    runOnUiThread {
                                        appState = importedState
                                        statusMessage = message
                                    }
                                },
                            )
                        },
                        onStop = { stopServer() },
                        onCancel = {
                            stopServer()
                            screen = Screen.List
                        },
                    )

                    is Screen.Detail -> DetailScreen(
                        authenticator = current.authenticator,
                        onBack = { screen = Screen.List },
                    )
                }
            }
            }
        })
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }

    override fun finish() {
        if (screenForBack != Screen.List) {
            handleBack?.invoke()
            return
        }
        super.finish()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            handleBackPress()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        handleBackPress()
    }

    private fun handleBackPress() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread { handleBackPress() }
            return
        }
        handleBack?.invoke() ?: finish()
    }

    private fun startServer(onStateChanged: (AppState, String) -> Unit) {
        if (uploadServer != null) return
        uploadServer = UploadHttpServer(text = uploadPageText()) { request -> handleUpload(request, onStateChanged) }
        thread(name = "wristotp-http-start", isDaemon = true) {
            try {
                uploadServer?.start()
            } catch (e: Exception) {
                onStateChanged(dataStore.load(), getString(R.string.http_service_failed, e.message.orEmpty()))
            }
        }
    }

    private fun stopServer() {
        uploadServer?.close()
        uploadServer = null
    }

    private fun handleUpload(request: UploadRequest, onStateChanged: (AppState, String) -> Unit): UploadResponse {
        return try {
            val imported = importer.import(request.fileName, request.data, request.password.ifBlank { null })
            dataStore.replaceImportedData(imported)
            val message = if (imported.skipped.isEmpty()) {
                getString(R.string.import_success)
            } else {
                getString(R.string.import_success_skipped, imported.skipped.size)
            }
            onStateChanged(dataStore.load(), message)
            UploadResponse(success = true, title = getString(R.string.import_success), message = message)
        } catch (e: ImportFailure.PasswordError) {
            val message = getString(R.string.password_error)
            onStateChanged(dataStore.load(), message)
            UploadResponse(success = false, title = message, message = message)
        } catch (e: ImportFailure.PasswordRequired) {
            val message = getString(R.string.backup_password)
            onStateChanged(dataStore.load(), message)
            UploadResponse(success = false, title = getString(R.string.import_failed), message = message)
        } catch (e: Exception) {
            val message = e.message ?: getString(R.string.import_failed)
            onStateChanged(dataStore.load(), message)
            UploadResponse(success = false, title = getString(R.string.import_failed), message = message)
        }
    }

    private fun findLanIpv4(): String {
        return runCatching {
            Collections.list(NetworkInterface.getNetworkInterfaces())
                .flatMap { Collections.list(it.inetAddresses) }
                .firstOrNull { address ->
                    !address.isLoopbackAddress &&
                        address.hostAddress?.contains('.') == true &&
                        !address.hostAddress.orEmpty().startsWith("169.254.")
                }
                ?.hostAddress
        }.getOrNull() ?: "192.168.x.x"
    }

    private fun uploadPageText(): UploadPageText = UploadPageText(
        uploadBackupFile = getString(R.string.upload_backup_file),
        backupFile = getString(R.string.backup_file),
        backupPassword = getString(R.string.backup_password),
        upload = getString(R.string.upload),
        importFailed = getString(R.string.import_failed),
        fileTooLarge = getString(R.string.file_too_large),
        noFileUploaded = getString(R.string.no_file_uploaded),
        missingMultipartBoundary = getString(R.string.missing_multipart_boundary),
        badRequest = getString(R.string.bad_request),
        notFound = getString(R.string.not_found),
    )
}

private sealed class Screen {
    data object List : Screen()
    data object Import : Screen()
    data class Detail(val authenticator: Authenticator) : Screen()
}

@Composable
private fun WristOtpTheme(content: @Composable () -> Unit) {
    MaterialTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            content()
        }
    }
}

@Composable
private fun MainScreen(
    appState: AppState,
    selectedCategoryId: String?,
    statusMessage: String?,
    onSelectCategory: (String?) -> Unit,
    onImport: () -> Unit,
    onOpenAuthenticator: (Authenticator) -> Unit,
    onReorderAuthenticators: (List<Authenticator>) -> Unit,
) {
    var drawerOpen by remember { mutableStateOf(false) }
    var now by remember { mutableStateOf(System.currentTimeMillis() / 1000) }

    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis() / 1000
            delay(1_000)
        }
    }

    val filtered = remember(appState.authenticators, selectedCategoryId) {
        val list = if (selectedCategoryId == null) {
            appState.authenticators
        } else {
            appState.authenticators.filter { selectedCategoryId in it.categoryIds }
        }
        list
    }
    val categoryNamesById = remember(appState.categories) {
        appState.categories.associate { it.id to it.name }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
    ) {
        if (filtered.isEmpty()) {
            EmptyState(statusMessage)
        } else {
            ReorderableAuthenticatorList(
                allAuthenticators = appState.authenticators,
                visibleAuthenticators = filtered,
                categoryNamesById = categoryNamesById,
                now = now,
                onOpenAuthenticator = onOpenAuthenticator,
                onReorderAuthenticators = onReorderAuthenticators,
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(48.dp)
                .pointerInput(Unit) {
                    detectVerticalDragGestures { _, dragAmount ->
                        if (dragAmount > 16) drawerOpen = true
                    }
                },
        )

        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(48.dp)
                .clickable { drawerOpen = true },
        )

        if (drawerOpen) {
            TopDrawer(
                categories = appState.categories,
                selectedCategoryId = selectedCategoryId,
                onSelectCategory = {
                    onSelectCategory(it)
                    drawerOpen = false
                },
                onImport = {
                    drawerOpen = false
                    onImport()
                },
                onDismiss = { drawerOpen = false },
            )
        }
    }
}

@Composable
private fun ReorderableAuthenticatorList(
    allAuthenticators: List<Authenticator>,
    visibleAuthenticators: List<Authenticator>,
    categoryNamesById: Map<String, String>,
    now: Long,
    onOpenAuthenticator: (Authenticator) -> Unit,
    onReorderAuthenticators: (List<Authenticator>) -> Unit,
) {
    val listState = rememberLazyListState()
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragOffsetY by remember { mutableStateOf(0f) }
    var edgeScrollPx by remember { mutableStateOf(0f) }
    var pointerY by remember { mutableStateOf(0f) }
    var touchOffsetInItem by remember { mutableStateOf(0f) }
    val latestAllAuthenticators by rememberUpdatedState(allAuthenticators)
    val latestVisibleAuthenticators by rememberUpdatedState(visibleAuthenticators)
    val latestOnReorderAuthenticators by rememberUpdatedState(onReorderAuthenticators)

    fun updateDraggedOffset(id: String) {
        val visible = latestVisibleAuthenticators
        val currentIndex = visible.indexOfFirst { it.id == id }
        if (currentIndex == -1) return

        val currentItem = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == currentIndex }
            ?: return
        dragOffsetY = pointerY - touchOffsetInItem - currentItem.offset
    }

    fun maybeMoveDraggedItem(id: String) {
        val visible = latestVisibleAuthenticators
        val currentIndex = visible.indexOfFirst { it.id == id }
        if (currentIndex == -1) return

        val visibleItems = listState.layoutInfo.visibleItemsInfo
        val currentItem = visibleItems.firstOrNull { it.index == currentIndex }
            ?: return
        val draggedCenter = pointerY - touchOffsetInItem + (currentItem.size / 2f)
        val hysteresisPx = (currentItem.size * 0.08f).coerceIn(4f, 12f)
        val previousItem = visibleItems.firstOrNull { it.index == currentIndex - 1 }
        if (previousItem != null) {
            val previousCenter = previousItem.offset + (previousItem.size / 2f)
            if (draggedCenter < previousCenter - hysteresisPx) {
                latestOnReorderAuthenticators(
                    reorderVisibleAuthenticators(
                        allAuthenticators = latestAllAuthenticators,
                        visibleAuthenticators = visible,
                        fromIndex = currentIndex,
                        toIndex = currentIndex - 1,
                    ),
                )
                return
            }
        }

        val nextItem = visibleItems.firstOrNull { it.index == currentIndex + 1 }
        if (nextItem != null) {
            val nextCenter = nextItem.offset + (nextItem.size / 2f)
            if (draggedCenter > nextCenter + hysteresisPx) {
                latestOnReorderAuthenticators(
                    reorderVisibleAuthenticators(
                        allAuthenticators = latestAllAuthenticators,
                        visibleAuthenticators = visible,
                        fromIndex = currentIndex,
                        toIndex = currentIndex + 1,
                    ),
                )
            }
        }
    }

    LaunchedEffect(draggingId, edgeScrollPx) {
        val id = draggingId ?: return@LaunchedEffect
        while (draggingId == id && edgeScrollPx != 0f) {
            val consumed = listState.scrollBy(edgeScrollPx)
            if (consumed != 0f) {
                updateDraggedOffset(id)
                maybeMoveDraggedItem(id)
            }
            delay(16)
        }
    }

    LaunchedEffect(visibleAuthenticators, draggingId) {
        val id = draggingId ?: return@LaunchedEffect
        delay(0)
        updateDraggedOffset(id)
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 22.dp, top = 24.dp, end = 22.dp, bottom = 22.dp)
            .pointerInput(Unit) {
                val edgeSize = 42.dp.toPx()
                fun edgeScrollDelta(pointerY: Float): Float {
                    val maxStep = 6.dp.toPx()
                    return when {
                        pointerY < edgeSize -> {
                            val intensity = ((edgeSize - pointerY) / edgeSize).coerceIn(0.12f, 1f)
                            -maxStep * intensity
                        }

                        pointerY > size.height - edgeSize -> {
                            val intensity = ((pointerY - (size.height - edgeSize)) / edgeSize).coerceIn(0.12f, 1f)
                            maxStep * intensity
                        }

                        else -> 0f
                    }
                }

                detectDragGesturesAfterLongPress(
                    onDragStart = { offset ->
                        pointerY = offset.y
                        val touched = listState.findVisibleItemAt(offset.y)
                        val visible = latestVisibleAuthenticators
                        if (touched != null && touched.index in visible.indices) {
                            draggingId = visible[touched.index].id
                            touchOffsetInItem = offset.y - touched.offset
                            dragOffsetY = 0f
                            edgeScrollPx = edgeScrollDelta(pointerY)
                        }
                    },
                    onDragCancel = {
                        draggingId = null
                        dragOffsetY = 0f
                        edgeScrollPx = 0f
                        touchOffsetInItem = 0f
                    },
                    onDragEnd = {
                        draggingId = null
                        dragOffsetY = 0f
                        edgeScrollPx = 0f
                        touchOffsetInItem = 0f
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val id = draggingId ?: return@detectDragGesturesAfterLongPress
                        pointerY += dragAmount.y
                        edgeScrollPx = edgeScrollDelta(pointerY)
                        updateDraggedOffset(id)
                        maybeMoveDraggedItem(id)
                    },
                )
            },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        itemsIndexed(visibleAuthenticators, key = { _, auth -> auth.id }) { index, auth ->
            val isDragging = draggingId == auth.id
            val placementModifier = if (isDragging) {
                Modifier
            } else {
                Modifier.animateItem(
                    placementSpec = tween(
                        durationMillis = REORDER_PLACEMENT_ANIMATION_MS,
                        easing = FastOutSlowInEasing,
                    ),
                )
            }
            val itemDragOffset = if (isDragging) {
                val layoutItem = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
                if (layoutItem != null) {
                    pointerY - touchOffsetInItem - layoutItem.offset
                } else {
                    dragOffsetY
                }
            } else {
                0f
            }
            AuthenticatorRow(
                authenticator = auth,
                categoryNames = auth.categoryIds.mapNotNull(categoryNamesById::get),
                now = now,
                onClick = onOpenAuthenticator,
                modifier = placementModifier
                    .offset { IntOffset(0, itemDragOffset.roundToInt()) }
                    .zIndex(if (isDragging) 1f else 0f),
            )
        }
    }
}

private fun LazyListState.findVisibleItemAt(y: Float) = layoutInfo.visibleItemsInfo.firstOrNull { item ->
    y >= item.offset && y <= item.offset + item.size
}

private fun reorderVisibleAuthenticators(
    allAuthenticators: List<Authenticator>,
    visibleAuthenticators: List<Authenticator>,
    fromIndex: Int,
    toIndex: Int,
): List<Authenticator> {
    if (fromIndex == toIndex || fromIndex !in visibleAuthenticators.indices || toIndex !in visibleAuthenticators.indices) {
        return allAuthenticators
    }

    val reorderedVisible = visibleAuthenticators.toMutableList().apply {
        add(toIndex, removeAt(fromIndex))
    }
    val visibleIds = visibleAuthenticators.mapTo(mutableSetOf()) { it.id }
    val replacement = reorderedVisible.iterator()
    return allAuthenticators.map { authenticator ->
        if (authenticator.id in visibleIds) replacement.next() else authenticator
    }
}

@Composable
private fun EmptyState(statusMessage: String?) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.no_authenticators),
            color = Color.White,
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
        )
        if (!statusMessage.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = statusMessage,
                color = Color(0xffbcbcbc),
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun AuthenticatorRow(
    authenticator: Authenticator,
    categoryNames: List<String>,
    now: Long,
    onClick: (Authenticator) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isTotp = authenticator.type == OtpType.TOTP
    val remaining = if (isTotp) OtpGenerator.remainingSeconds(authenticator, now) else 0

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xff050505))
            .clickable { onClick(authenticator) }
    ) {
        Column(modifier = Modifier.padding(horizontal = 5.dp, vertical = 3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBubble(iconId = authenticator.iconId, fallbackName = authenticator.issuer)
                Spacer(Modifier.width(5.dp))
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = authenticator.issuer,
                        color = Color.White,
                        fontSize = 13.sp,
                        lineHeight = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (categoryNames.isNotEmpty()) {
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = categoryNames.joinToString(" · "),
                            color = Color(0xff2CB1EC),
                            fontSize = 9.sp,
                            lineHeight = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.End,
                            modifier = Modifier.widthIn(max = 72.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(1.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 27.dp, end = 8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = authenticator.username.orEmpty(),
                    color = Color(0xffa7a7a7),
                    fontSize = 11.sp,
                    lineHeight = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(top = 2.dp),
                )
                Spacer(Modifier.width(6.dp))
                if (isTotp) {
                    Text(
                        text = OtpGenerator.code(authenticator, now),
                        color = Color.White,
                        fontSize = 22.sp,
                        lineHeight = 22.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        softWrap = false,
                        textAlign = TextAlign.End,
                    )
                } else {
                    Text(
                        text = "HOTP",
                        color = Color(0xff2CB1EC),
                        fontSize = 13.sp,
                        lineHeight = 14.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            if (isTotp) {
                Spacer(Modifier.height(2.dp))
            }
        }

        if (isTotp) {
            HorizontalCountdownBar(
                remaining = remaining,
                period = authenticator.period,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .padding(horizontal = 5.dp),
            )
        }
    }
}

@Composable
private fun IconBubble(
    iconId: String?,
    fallbackName: String,
    size: Dp = 22.dp,
    imageSize: Dp = 20.dp,
    textSize: TextUnit = 11.sp,
) {
    val context = LocalContext.current
    val iconFile = remember(iconId) {
        iconId?.let { java.io.File(context.filesDir, "icons/$it") }?.takeIf { it.exists() }
    }
    val bitmap = remember(iconFile) {
        iconFile?.let { runCatching { BitmapFactory.decodeFile(it.absolutePath) }.getOrNull() }
    }
    val fallbackColor = remember(fallbackName) { fallbackIconColor(fallbackName) }
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (bitmap != null) Color(0xff161616) else fallbackColor.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(bitmap = bitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.size(imageSize))
        } else {
            Text(
                fallbackInitial(fallbackName),
                color = fallbackColor,
                fontSize = textSize,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

private fun fallbackInitial(name: String): String {
    return name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
}

private fun fallbackIconColor(name: String): Color {
    val palette = listOf(
        Color(0xff2CB1EC),
        Color(0xff7BD88F),
        Color(0xffB7A6FF),
        Color(0xffF2A63B),
        Color(0xff75D6C1),
        Color(0xffEE5D50),
    )
    val index = (name.trim().lowercase().hashCode() and Int.MAX_VALUE) % palette.size
    return palette[index]
}

@Composable
private fun TopDrawer(
    categories: List<Category>,
    selectedCategoryId: String?,
    onSelectCategory: (String?) -> Unit,
    onImport: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xee141414))
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        DrawerItem(
            text = stringResource(R.string.all),
            selected = selectedCategoryId == null,
            onClick = { onSelectCategory(null) },
        )
        DrawerItem(
            text = stringResource(R.string.import_backup),
            selected = false,
            onClick = onImport,
        )
        categories.sortedBy { it.order }.forEach { category ->
            DrawerItem(
                text = category.name,
                selected = selectedCategoryId == category.id,
                onClick = { onSelectCategory(category.id) },
            )
        }
        DrawerItem(text = stringResource(R.string.cancel), selected = false, onClick = onDismiss)
    }
}

@Composable
private fun DrawerItem(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        color = if (selected) Color(0xff2CB1EC) else Color.White,
        fontSize = 14.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 5.dp),
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun DetailScreen(authenticator: Authenticator, onBack: () -> Unit) {
    var now by remember { mutableStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis() / 1000
            delay(1_000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(onBack) {
                val threshold = 54.dp.toPx()
                var totalDragX = 0f
                detectHorizontalDragGestures(
                    onDragStart = { totalDragX = 0f },
                    onDragCancel = { totalDragX = 0f },
                    onDragEnd = {
                        if (totalDragX > threshold) {
                            onBack()
                        }
                        totalDragX = 0f
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        totalDragX += dragAmount
                        if (totalDragX > 8f || totalDragX < -8f) {
                            change.consume()
                        }
                    },
                )
            }
            .clickable(onClick = onBack)
            .padding(18.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconBubble(authenticator.iconId, authenticator.issuer, size = 34.dp, imageSize = 30.dp, textSize = 16.sp)
        Spacer(Modifier.height(10.dp))
        Text(authenticator.issuer, color = Color.White, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val username = authenticator.username
        if (!username.isNullOrBlank()) {
            Text(username, color = Color(0xff9a9a9a), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(8.dp))
        if (authenticator.type == OtpType.TOTP) {
            val remaining = OtpGenerator.remainingSeconds(authenticator, now)
            Text(
                text = OtpGenerator.code(authenticator, now),
                color = Color.White,
                fontSize = 34.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            HorizontalCountdownBar(
                remaining = remaining,
                period = authenticator.period,
                modifier = Modifier
                    .width(125.dp)
                    .height(3.dp),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "${remaining}s",
                color = countdownColor(remaining),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
        } else {
            Text(stringResource(R.string.hotp_not_supported), color = Color.White, fontSize = 15.sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun HorizontalCountdownBar(remaining: Int, period: Int, modifier: Modifier = Modifier) {
    val progress = (remaining.toFloat() / period.coerceAtLeast(1)).coerceIn(0f, 1f)
    Box(
        modifier = modifier.background(Color(0xff25282c)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress)
                .fillMaxHeight()
                .background(countdownColor(remaining)),
        )
    }
}

private fun countdownColor(remaining: Int): Color = if (remaining <= 5) Color(0xfff2a63b) else Color(0xff2CB1EC)

@Composable
private fun ImportScreen(
    address: String,
    statusMessage: String?,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onCancel: () -> Unit,
) {
    DisposableEffect(Unit) {
        onStart()
        onDispose { onStop() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.upload_backup_file), color = Color.White, fontSize = 16.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(address, color = Color(0xff2CB1EC), fontSize = 13.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(statusMessage ?: stringResource(R.string.waiting_for_upload), color = Color(0xffbcbcbc), fontSize = 12.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(14.dp))
        Button(onClick = onCancel) {
            Text(stringResource(R.string.cancel))
        }
    }
}
