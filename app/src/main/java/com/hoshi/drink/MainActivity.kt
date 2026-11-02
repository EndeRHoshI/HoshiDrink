package com.hoshi.drink

import android.Manifest
import android.app.TimePickerDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin

private val Forest = Color(0xFF26765B)
private val Ink = Color(0xFF223B32)
private val Muted = Color(0xFF748078)
private val Mint = Color(0xFFE5F1EB)
private val Paper = Color(0xFFF7F9F7)
private val Coral = Color(0xFFCE795F)
private val Line = Color(0xFFE1E7E2)
private val Scheme = lightColorScheme(primary = Forest, onPrimary = Color.White, primaryContainer = Mint,
    onPrimaryContainer = Ink, secondary = Coral, onSecondary = Color.White, secondaryContainer = Mint,
    onSecondaryContainer = Forest, tertiary = Coral, background = Paper, onBackground = Ink,
    surface = Paper, onSurface = Ink, onSurfaceVariant = Muted, surfaceVariant = Line,
    surfaceContainer = Color(0xFFF0F4F0), surfaceContainerHigh = Color(0xFFEDF2ED),
    surfaceContainerHighest = Line, surfaceContainerLow = Paper, surfaceContainerLowest = Color.White,
    inverseSurface = Ink, inverseOnSurface = Paper, surfaceTint = Forest,
    outline = Color(0xFFBAC8BE), outlineVariant = Line)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.action == Reminders.ACTION_OPEN_AND_STOP) Reminders.stopVibration(this)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(
                scrim = android.graphics.Color.TRANSPARENT,
                darkScrim = android.graphics.Color.TRANSPARENT,
            ),
            navigationBarStyle = SystemBarStyle.light(
                scrim = android.graphics.Color.TRANSPARENT,
                darkScrim = android.graphics.Color.BLACK,
            ),
        )
        Reminders.createChannel(this)
        setContent { MaterialTheme(colorScheme = Scheme) { DrinkApp() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == Reminders.ACTION_OPEN_AND_STOP) Reminders.stopVibration(this)
    }
}

@Composable
private fun DrinkApp(model: DrinkViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var today by remember { mutableStateOf(LocalDate.now()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) { today = LocalDate.now(); model.refresh() }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) {
        while (true) { delay(30_000); today = LocalDate.now() }
    }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(state.error) {
        state.error?.let { snackbar.showSnackbar(it); model.clearError() }
    }
    Scaffold(
        containerColor = Paper,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Surface(color = Paper) {
                Column {
                    HorizontalDivider(color = Line)
                    NavigationBar(containerColor = Paper, tonalElevation = 0.dp) {
                        val labels = listOf("今日", "统计", "设置")
                        val icons = listOf(Icons.Outlined.WaterDrop, Icons.Outlined.BarChart, Icons.Outlined.Tune)
                        labels.forEachIndexed { index, label ->
                            NavigationBarItem(selected = tab == index, onClick = { tab = index },
                                icon = { Icon(icons[index], label) }, label = { Text(label) },
                                colors = NavigationBarItemDefaults.colors(indicatorColor = Mint, selectedIconColor = Forest, selectedTextColor = Forest))
                        }
                    }
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            if (!state.loaded) CircularProgressIndicator(Modifier.padding(48.dp))
            else Column(Modifier.widthIn(max = 600.dp).fillMaxWidth().fillMaxHeight()) {
                when (tab) {
                    0 -> TodayScreen(state, today, onAdd = model::add, onSettings = { tab = 2 }, onDelete = { drink ->
                        model.delete(drink)
                        scope.launch {
                            if (snackbar.showSnackbar("已删除 ${drink.amount} mL", "撤销", duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) model.restore(drink)
                        }
                    })
                    1 -> StatsScreen(state, today, onDelete = { drink ->
                        model.delete(drink)
                        scope.launch {
                            if (snackbar.showSnackbar("记录已删除", "撤销", duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) model.restore(drink)
                        }
                    })
                    else -> SettingsScreen(state.settings, model::settings, snackbar)
                }
            }
        }
    }
}

@Composable
private fun TodayScreen(state: DrinkState, today: LocalDate, onAdd: (Int) -> Unit, onSettings: () -> Unit, onDelete: (Drink) -> Unit) {
    val notificationsAllowed = Reminders.permission(LocalContext.current)
    val drinks = state.drinks.filter { it.date == today }
    val total = drinks.sumOf { it.amount }
    val goal = state.settings.goal
    var amount by rememberSaveable { mutableIntStateOf(250) }
    var custom by rememberSaveable { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    Box {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("HoshiDrink", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Ink)
                    Text(today.format(DateTimeFormatter.ofPattern("M月d日 · EEEE", Locale.CHINA)), color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 5.dp))
                }
                IconButton(onClick = onSettings) { Icon(if (state.settings.enabled) Icons.Outlined.NotificationsActive else Icons.Outlined.NotificationsNone, "提醒设置", tint = Forest) }
            }
            Text(if (total >= goal) "今日目标，完成了" else "喝一点水，给自己充充电", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Text(if (total >= goal) "每一杯都算数，保持这个好习惯。" else "慢慢来，每一杯都算数。", color = Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 7.dp, bottom = 22.dp))
            Row(Modifier.fillMaxWidth().heightIn(min = 245.dp).background(Mint, RoundedCornerShape(8.dp)).padding(22.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("今日已饮水", color = Forest, fontSize = 13.sp)
                    Text("$total", fontSize = if (total < 10000) 42.sp else 32.sp, fontWeight = FontWeight.Bold, color = Ink, modifier = Modifier.padding(top = 7.dp))
                    Text("/ $goal mL", color = Muted, fontSize = 15.sp)
                    Spacer(Modifier.height(22.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (total >= goal) Icons.Outlined.CheckCircle else Icons.AutoMirrored.Outlined.TrendingUp, null, tint = Forest, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("${total * 100 / goal}%", color = Forest, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                    Text(if (total >= goal) "今天也照顾好自己了" else "还差 ${goal - total} mL", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                }
                WaterBottle(total.toFloat() / goal, Modifier.width(114.dp).height(205.dp))
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Schedule, null, tint = Coral, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(when {
                    !state.settings.enabled -> "饮水提醒未开启"
                    !notificationsAllowed -> "系统通知未开启"
                    total >= goal && state.settings.stopAtGoal -> "今日提醒已暂停 · 明天继续"
                    else -> "每 ${state.settings.interval} 分钟 · ${clockText(state.settings.start)}–${clockText(state.settings.end)}"
                }, color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = onSettings, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(if (state.settings.enabled) "调整" else "开启", fontSize = 12.sp) }
            }
            SectionTitle("记下一杯", "选择容量")
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(150, 250, 350).forEach { volume ->
                    val selected = amount == volume
                    OutlinedButton(onClick = { amount = volume }, modifier = Modifier.weight(1f).height(70.dp),
                        shape = RoundedCornerShape(8.dp), contentPadding = PaddingValues(4.dp),
                        colors = ButtonDefaults.outlinedButtonColors(containerColor = if (selected) Mint else Color.Transparent, contentColor = if (selected) Forest else Muted),
                        border = androidx.compose.foundation.BorderStroke(1.dp, if (selected) Forest else Line)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Outlined.LocalDrink, null, modifier = Modifier.size(21.dp))
                            Text("$volume mL", fontSize = 13.sp, modifier = Modifier.padding(top = 5.dp))
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { custom = true }) {
                    Icon(Icons.Outlined.Add, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("自定义容量", fontSize = 12.sp)
                }
            }
            Button(onClick = { onAdd(amount); haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
                modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(8.dp)) {
                Icon(Icons.Outlined.Add, null, Modifier.size(22.dp)); Spacer(Modifier.width(9.dp)); Text("记录 $amount mL", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(30.dp))
            SectionTitle("今日记录", "${drinks.size} 杯")
            DrinkList(drinks, onDelete)
            Spacer(Modifier.height(24.dp))
        }
    }
    if (custom) NumberDialog("自定义容量", "饮水量", amount, 1..2000, "1–2000 mL", onDismiss = { custom = false }) { amount = it; custom = false }
}

@Composable
private fun WaterBottle(progress: Float, modifier: Modifier = Modifier) {
    val fill by animateFloatAsState(progress.coerceIn(0f, 1f), tween(900), label = "water-level")
    val animation = rememberInfiniteTransition(label = "water")
    val phase by animation.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(3400, easing = LinearEasing)), label = "wave")
    Canvas(modifier.semantics { contentDescription = "水瓶水位，已完成 ${(progress * 100).toInt()}%" }) {
        val w = size.width
        val h = size.height
        val bottle = Path().apply {
            moveTo(w * .34f, h * .12f); lineTo(w * .66f, h * .12f)
            lineTo(w * .66f, h * .23f)
            cubicTo(w * .66f, h * .29f, w * .91f, h * .29f, w * .91f, h * .43f)
            lineTo(w * .91f, h * .88f); quadraticTo(w * .91f, h * .97f, w * .76f, h * .97f)
            lineTo(w * .24f, h * .97f); quadraticTo(w * .09f, h * .97f, w * .09f, h * .88f)
            lineTo(w * .09f, h * .43f)
            cubicTo(w * .09f, h * .29f, w * .34f, h * .29f, w * .34f, h * .23f); close()
        }
        drawPath(bottle, Color.White.copy(alpha = .62f))
        clipPath(bottle) {
            val waterY = h * (.95f - .7f * fill)
            fun wave(offset: Float, amplitude: Float): Path = Path().apply {
                moveTo(0f, h)
                lineTo(0f, waterY)
                for (x in 0..100) {
                    val px = w * x / 100f
                    lineTo(px, waterY + sin(x / 100f * 2 * PI + phase + offset).toFloat() * amplitude)
                }
                lineTo(w, h); close()
            }
            if (fill > 0) {
                drawPath(wave(1.8f, h * .018f), Color(0xFF98CDB8))
                drawPath(wave(0f, h * .014f), Color(0xFF4C9C7E).copy(alpha = .88f))
            }
            drawRoundRect(Color.White.copy(alpha = .42f), Offset(w * .23f, h * .4f), Size(w * .06f, h * .37f), CornerRadius(w * .03f))
            for (i in 0..3) drawLine(Forest.copy(alpha = .25f), Offset(w * .7f, h * (.45f + i * .12f)), Offset(w * .8f, h * (.45f + i * .12f)), 1.5.dp.toPx())
        }
        drawPath(bottle, Forest.copy(alpha = .35f), style = Stroke(1.5.dp.toPx()))
        drawRoundRect(Forest, Offset(w * .29f, h * .035f), Size(w * .42f, h * .1f), CornerRadius(5.dp.toPx()))
        for (i in 0..3) drawLine(Color.White.copy(alpha = .18f), Offset(w * (.36f + i * .09f), h * .055f), Offset(w * (.36f + i * .09f), h * .115f), 1.dp.toPx())
    }
}

@Composable
private fun SectionTitle(title: String, caption: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text(caption, fontSize = 12.sp, color = Muted)
    }
}

@Composable
private fun DrinkList(drinks: List<Drink>, onDelete: (Drink) -> Unit) {
    if (drinks.isEmpty()) {
        Column(Modifier.fillMaxWidth().padding(vertical = 30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.LocalDrink, null, tint = Forest.copy(alpha = .5f), modifier = Modifier.size(30.dp))
            Text("还没有饮水记录", color = Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp))
        }
    } else drinks.forEach { drink ->
        Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp).background(Mint, CircleShape), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.WaterDrop, null, tint = Forest, modifier = Modifier.size(19.dp)) }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text("${drink.amount} mL", fontWeight = FontWeight.Medium, fontSize = 15.sp)
                Text(Instant.ofEpochMilli(drink.timestamp).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm")), fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 3.dp))
            }
            IconButton(onClick = { onDelete(drink) }) { Icon(Icons.Outlined.DeleteOutline, "删除 ${drink.amount} mL 记录", tint = Muted, modifier = Modifier.size(19.dp)) }
        }
        HorizontalDivider(color = Line)
    }
}

@Composable
private fun StatsScreen(state: DrinkState, today: LocalDate, onDelete: (Drink) -> Unit) {
    var weekOffset by rememberSaveable { mutableIntStateOf(0) }
    var selectedEpoch by rememberSaveable(today) { mutableLongStateOf(today.toEpochDay()) }
    val selected = LocalDate.ofEpochDay(selectedEpoch)
    val end = today.minusWeeks(weekOffset.toLong())
    val dates = (6 downTo 0).map { end.minusDays(it.toLong()) }
    val totals = dates.map { date -> state.drinks.filter { it.date == date }.sumOf { it.amount } }
    val goal = state.settings.goal
    val reached = totals.count { it >= goal }
    val selectedDrinks = state.drinks.filter { it.date == selected }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
        ScreenHeading("饮水统计", "把每一天的小习惯，变成看得见的坚持。")
        Row(Modifier.fillMaxWidth().padding(bottom = 26.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Metric("日均饮水", "${totals.sum() / 7}", "mL", Forest)
            Metric("达标天数", "$reached", "/ 7 天", Coral)
            Metric("累计记录", "${state.drinks.count { it.date in dates }}", "杯", Ink)
        }
        HorizontalDivider(color = Line)
        Row(Modifier.fillMaxWidth().padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (weekOffset == 0) "最近 7 天" else "往期记录", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text("${dates.first().format(DateTimeFormatter.ofPattern("M.d"))} – ${end.format(DateTimeFormatter.ofPattern("M.d"))}", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp))
            }
            IconButton(onClick = { weekOffset++; selectedEpoch = end.minusWeeks(1).toEpochDay() }) { Icon(Icons.Outlined.ChevronLeft, "前七天") }
            IconButton(onClick = { weekOffset--; selectedEpoch = end.plusWeeks(1).toEpochDay() }, enabled = weekOffset > 0) { Icon(Icons.Outlined.ChevronRight, "后七天") }
        }
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("mL", color = Muted, fontSize = 11.sp)
            Text("${selected.monthValue}月${selected.dayOfMonth}日 · ${selectedDrinks.sumOf { it.amount }} mL", color = Forest, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
        val max = maxOf(goal, totals.maxOrNull() ?: 0).toFloat()
        Box(Modifier.fillMaxWidth().padding(top = 10.dp)) {
            Column(Modifier.fillMaxWidth().height(168.dp), verticalArrangement = Arrangement.SpaceBetween) {
                repeat(4) { HorizontalDivider(color = Line) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                dates.forEachIndexed { index, date ->
                    val value = totals[index]
                    val active = date == selected
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(4.dp)).clickable { selectedEpoch = date.toEpochDay() }
                        .semantics { contentDescription = "${date.monthValue}月${date.dayOfMonth}日，${value}毫升" }, horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.height(168.dp).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                            Box(Modifier.widthIn(max = 28.dp).fillMaxWidth(.75f).height(maxOf(4f, 152 * value / max).dp)
                                .background(if (active) Forest else if (value >= goal) Color(0xFF89B8A4) else Color(0xFFCEDDD4), RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)))
                        }
                        Text(if (date == today) "今天" else "${date.dayOfMonth}", color = if (active) Forest else Muted, fontSize = 11.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.padding(vertical = 12.dp))
                    }
                }
            }
        }
        Row(Modifier.padding(top = 12.dp, bottom = 28.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).background(Forest, CircleShape)); Spacer(Modifier.width(6.dp))
            Text("按当前目标 $goal mL 统计达标天数", color = Muted, fontSize = 11.sp)
        }
        Row(Modifier.fillMaxWidth().background(Mint, RoundedCornerShape(8.dp)).padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (reached > 0) Icons.Outlined.EmojiEvents else Icons.Outlined.Spa, null, tint = Forest, modifier = Modifier.size(26.dp))
            Column(Modifier.padding(start = 12.dp)) {
                Text(if (reached > 0) "这七天，$reached 天达成目标" else "坚持，从一杯水开始", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(if (reached > 0) "让照顾自己成为日常。" else "每一次记录，都是一个好的开始。", fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 4.dp))
            }
        }
        Spacer(Modifier.height(30.dp))
        SectionTitle("${selected.monthValue}月${selected.dayOfMonth}日", "共 ${selectedDrinks.sumOf { it.amount }} mL")
        DrinkList(selectedDrinks, onDelete)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Metric(label: String, value: String, unit: String, color: Color) {
    Column {
        Text(label, color = Muted, fontSize = 12.sp)
        Text(value, color = color, fontSize = 29.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 9.dp))
        Text(unit, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun ScreenHeading(title: String, subtitle: String) {
    Text(title, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 26.dp))
    Text(subtitle, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp, bottom = 30.dp))
}

@Composable
private fun SettingsScreen(settings: DrinkSettings, onSettings: (DrinkSettings) -> Unit, snackbar: SnackbarHostState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var goalDialog by rememberSaveable { mutableStateOf(false) }
    var permission by remember(settings.reminderStyle) { mutableStateOf(Reminders.permission(context, settings.reminderStyle)) }
    var exactAllowed by remember { mutableStateOf(Reminders.exactAllowed(context)) }
    var unrestricted by remember { mutableStateOf(context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)) }
    var styleMenu by remember { mutableStateOf(false) }
    var pendingPreview by rememberSaveable { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, settings.reminderStyle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) {
            permission = Reminders.permission(context, settings.reminderStyle)
            exactAllowed = Reminders.exactAllowed(context)
            unrestricted = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
        } }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    fun openSettings(intent: Intent) {
        try { context.startActivity(intent) }
        catch (_: ActivityNotFoundException) { scope.launch { snackbar.showSnackbar("请在系统应用设置中管理 HoshiDrink 的权限") } }
    }
    fun preview() {
        val sent = Reminders.notify(context, 0, settings, preview = true)
        scope.launch { snackbar.showSnackbar(if (sent) "测试通知已发送" else "请先允许系统通知") }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permission = granted && Reminders.permission(context, settings.reminderStyle)
        if (permission) {
            if (pendingPreview) preview() else onSettings(settings.copy(enabled = true))
        }
        else scope.launch { snackbar.showSnackbar("通知权限未开启，可在系统设置中允许通知") }
    }
    fun notificationAction(test: Boolean) {
        pendingPreview = test
        Reminders.createChannel(context, settings.reminderStyle)
        when {
            Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED -> launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            Reminders.permission(context, settings.reminderStyle) -> if (test) preview() else onSettings(settings.copy(enabled = true))
            else -> openSettings(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        }
    }
    fun enable(value: Boolean) {
        if (!value) onSettings(settings.copy(enabled = false))
        else notificationAction(false)
    }
    fun requestExactPermission() {
        if (Build.VERSION.SDK_INT >= 31) openSettings(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
    }
    fun pickTime(start: Boolean) {
        val time = if (start) settings.start else settings.end
        TimePickerDialog(context, { _, hour, minute ->
            val value = hour * 60 + minute
            if ((start && value >= settings.end) || (!start && value <= settings.start)) {
                scope.launch { snackbar.showSnackbar("结束时间需要晚于开始时间") }
            } else onSettings(if (start) settings.copy(start = value) else settings.copy(end = value))
        }, time / 60, time % 60, true).show()
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
        ScreenHeading("按自己的节奏", "一点点调整，让喝水更适合你的生活。")
        SectionTitle("每日目标", "")
        Row(Modifier.fillMaxWidth().clickable { goalDialog = true }.padding(vertical = 22.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Flag, null, tint = Forest, modifier = Modifier.size(23.dp))
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text("饮水目标", fontSize = 15.sp)
                Text("根据个人情况调整", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp))
            }
            Text("${settings.goal} mL", color = Forest, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Icon(Icons.Outlined.ChevronRight, null, tint = Muted, modifier = Modifier.padding(start = 6.dp).size(19.dp))
        }
        HorizontalDivider(color = Line)
        Spacer(Modifier.height(28.dp))
        SectionTitle("饮水提醒", "")
        OutlinedButton(onClick = {
            Reminders.createChannel(context, ReminderStyle.VIBRATE)
            onSettings(settings.copy(start = 10 * 60, end = 18 * 60 + 30, interval = 60,
                reminderStyle = ReminderStyle.VIBRATE, preciseReminders = true, stopAtGoal = false))
            scope.launch { snackbar.showSnackbar(if (settings.enabled) "已设置整点震动" else "已设置整点震动，开启提醒后生效") }
        }, modifier = Modifier.padding(top = 14.dp), shape = RoundedCornerShape(6.dp)) {
            Icon(Icons.Outlined.Alarm, null, Modifier.size(17.dp))
            Spacer(Modifier.width(6.dp))
            Text("10:00–18:30 整点震动", fontSize = 12.sp)
        }
        SettingSwitch("开启提醒", "在设定的时间里，记得补充水分", Icons.Outlined.NotificationsNone, settings.enabled, ::enable)
        if (!permission) {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Color(0xFFF7EDE7))
                .clickable { openSettings(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }
                .padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Info, null, tint = Coral, modifier = Modifier.size(18.dp))
                Text("系统通知未开启", Modifier.weight(1f).padding(start = 8.dp), color = Ink, fontSize = 12.sp)
                Text("去设置", color = Coral, fontSize = 12.sp)
                Icon(Icons.Outlined.ChevronRight, null, tint = Coral, modifier = Modifier.size(17.dp))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Vibration, null, tint = Forest, modifier = Modifier.size(23.dp))
            Text("提醒方式", fontSize = 14.sp, modifier = Modifier.weight(1f).padding(start = 13.dp))
            Box {
                TextButton(onClick = { styleMenu = true }, modifier = Modifier.semantics { contentDescription = "选择提醒方式" }) {
                    Text(settings.reminderStyle.label, fontSize = 14.sp)
                    Icon(Icons.Outlined.ArrowDropDown, null, modifier = Modifier.size(20.dp))
                }
                DropdownMenu(expanded = styleMenu, onDismissRequest = { styleMenu = false }) {
                    ReminderStyle.entries.forEach { style ->
                        DropdownMenuItem(text = { Text(style.label) }, onClick = {
                            styleMenu = false
                            Reminders.createChannel(context, style)
                            onSettings(settings.copy(reminderStyle = style))
                        }, trailingIcon = { if (settings.reminderStyle == style) Icon(Icons.Outlined.Check, null) })
                    }
                }
            }
        }
        if (settings.reminderStyle == ReminderStyle.VIBRATE) {
            SettingSwitch("持续震动", "规律循环，直到手动停止", Icons.Outlined.Vibration, settings.repeatVibration) {
                onSettings(settings.copy(repeatVibration = it))
            }
            if (settings.repeatVibration) {
                OutlinedButton(onClick = { Reminders.stopVibration(context) }, shape = RoundedCornerShape(6.dp)) {
                    Icon(Icons.Outlined.StopCircle, null, Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp)); Text("停止震动", fontSize = 12.sp)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { notificationAction(true) }, shape = RoundedCornerShape(6.dp)) {
                Icon(Icons.Outlined.NotificationsActive, null, Modifier.size(17.dp))
                Spacer(Modifier.width(6.dp)); Text("试发提醒", fontSize = 12.sp)
            }
            IconButton(onClick = {
                Reminders.createChannel(context, settings.reminderStyle)
                openSettings(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, settings.reminderStyle.channelId))
            }) { Icon(Icons.Outlined.Tune, "系统通知设置", tint = Forest, modifier = Modifier.size(20.dp)) }
        }
        Text("声音和通知震动以系统设置为准；静音震动仍受勿扰及设备震动设置限制。", fontSize = 11.sp, color = Muted, lineHeight = 17.sp, modifier = Modifier.padding(top = 5.dp))
        Spacer(Modifier.height(22.dp))
        Text("提醒间隔", fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(30, 60, 90, 120).forEach { interval ->
                FilterChip(selected = settings.interval == interval, onClick = { onSettings(settings.copy(interval = interval)) }, label = { Text("$interval 分钟", fontSize = 12.sp) }, shape = RoundedCornerShape(6.dp))
            }
        }
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TimeSetting("开始时间", settings.start, Modifier.weight(1f)) { pickTime(true) }
            TimeSetting("结束时间", settings.end, Modifier.weight(1f)) { pickTime(false) }
        }
        val dailyTimes = ReminderPolicy.dailyTimes(settings).map { it.format(DateTimeFormatter.ofPattern("HH:mm")) }
        val timeSummary = if (dailyTimes.size <= 12) dailyTimes.joinToString(" · ")
            else dailyTimes.take(3).joinToString(" · ") + " … " + dailyTimes.last() + "（共 ${dailyTimes.size} 次）"
        Text("每日提醒时刻", fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 16.dp))
        Text(timeSummary, fontSize = 13.sp, color = Forest, lineHeight = 21.sp, modifier = Modifier.padding(top = 7.dp, bottom = 12.dp))
        SettingSwitch("达标后暂停", "当天完成目标后，次日再提醒", Icons.Outlined.CheckCircle, settings.stopAtGoal) { onSettings(settings.copy(stopAtGoal = it)) }
        HorizontalDivider(color = Line)
        Spacer(Modifier.height(28.dp))
        SectionTitle("提醒可靠性", "")
        SettingSwitch("准时提醒", when {
            settings.preciseReminders && !exactAllowed -> "未允许闹钟权限，当前使用普通提醒"
            settings.preciseReminders -> "已启用，减少系统休眠造成的延迟"
            else -> "通过系统闹钟，减少提醒延迟"
        }, Icons.Outlined.Alarm, settings.preciseReminders) { value ->
            onSettings(settings.copy(preciseReminders = value))
            if (value && !exactAllowed) requestExactPermission()
        }
        if (settings.preciseReminders && !exactAllowed) {
            TextButton(onClick = ::requestExactPermission) { Text("允许闹钟和提醒权限", fontSize = 12.sp) }
        }
        Row(Modifier.fillMaxWidth().clickable {
            openSettings(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }.padding(vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.BatterySaver, null, tint = Forest, modifier = Modifier.size(23.dp))
            Column(Modifier.weight(1f).padding(horizontal = 13.dp)) {
                Text("电池优化", fontSize = 14.sp)
                Text(if (unrestricted) "已不受电池优化限制" else "允许后台运行可进一步减少延迟", fontSize = 11.sp, color = Muted, modifier = Modifier.padding(top = 5.dp))
            }
            Icon(Icons.Outlined.ChevronRight, "管理电池优化", tint = Muted, modifier = Modifier.size(20.dp))
        }
        TextButton(onClick = { openSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }) {
            Icon(Icons.Outlined.Settings, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("应用与后台设置", fontSize = 12.sp)
        }
        Text("部分机型需额外允许自启动和后台活动。强行停止应用后，需重新打开才能恢复提醒。", fontSize = 11.sp, lineHeight = 17.sp, color = Muted, modifier = Modifier.padding(top = 5.dp, bottom = 20.dp))
        HorizontalDivider(color = Line)
        Spacer(Modifier.height(28.dp))
        SectionTitle("关于", "v1.1.0")
        Row(Modifier.fillMaxWidth().padding(vertical = 20.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Outlined.Lock, null, tint = Forest, modifier = Modifier.size(22.dp))
            Column(Modifier.padding(start = 14.dp)) {
                Text("只属于你的饮水日记", fontSize = 14.sp)
                Text("记录保存在本机。卸载应用后数据会被清除。", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 7.dp))
            }
        }
        Text("每日目标仅用于习惯记录，并非医疗建议。饮水需求因人而异，请结合自身情况设置。", color = Muted, fontSize = 11.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 8.dp, bottom = 32.dp))
    }
    if (goalDialog) NumberDialog("每日饮水目标", "每日目标", settings.goal, 500..6000, "500–6000 mL", onDismiss = { goalDialog = false }) { onSettings(settings.copy(goal = it)); goalDialog = false }
}

@Composable
private fun TimeSetting(label: String, minutes: Int, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier) {
        Text(label, color = Muted, fontSize = 12.sp)
        OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(48.dp), shape = RoundedCornerShape(6.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Line)) {
            Icon(Icons.Outlined.Schedule, null, Modifier.size(17.dp)); Spacer(Modifier.width(8.dp)); Text(clockText(minutes), fontSize = 16.sp)
        }
    }
}

@Composable
private fun SettingSwitch(title: String, subtitle: String, icon: ImageVector, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 19.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Forest, modifier = Modifier.size(23.dp))
        Column(Modifier.weight(1f).padding(horizontal = 13.dp)) {
            Text(title, fontSize = 14.sp)
            Text(subtitle, color = Muted, fontSize = 11.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 5.dp))
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, modifier = Modifier.semantics { contentDescription = title })
    }
}

@Composable
private fun NumberDialog(title: String, label: String, initial: Int, range: IntRange, hint: String, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial.toString()) }
    val value = text.toIntOrNull()
    val valid = value != null && value in range
    AlertDialog(onDismissRequest = onDismiss, icon = { Icon(Icons.Outlined.WaterDrop, null) },
        title = { Text(title, fontSize = 21.sp) },
        text = {
            OutlinedTextField(value = text, onValueChange = { input -> if (input.length <= 4 && input.all { it.isDigit() }) text = input },
                label = { Text(label) }, suffix = { Text("mL") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = !valid,
                supportingText = { Text(hint) })
        }, confirmButton = { TextButton(onClick = { value?.let(onConfirm) }, enabled = valid) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

private fun clockText(minutes: Int) = String.format(Locale.ROOT, "%02d:%02d", minutes / 60, minutes % 60)
