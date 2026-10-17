package com.quicklauncher.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** "Search apps" system shortcut: type a name and open any installed app, whether or not it was added to the popup. */
class AppSearchActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AppTheme { AppSearch { finish() } } }
    }
}

@Composable
private fun AppSearch(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val apps = remember { Apps.peek() ?: Apps.list(ctx) }
    var q by remember { mutableStateOf("") }
    val shown = remember(q) { if (q.isBlank()) apps else apps.filter { it.label.contains(q, true) || it.pkg.contains(q, true) } }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    fun open(a: AppInfo) {
        val i = ctx.packageManager.getLaunchIntentForPackage(a.pkg)
        if (i == null) ctx.toast("App not installed") else ctx.startSafe(i)
        onClose()
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.padding(16.dp).widthIn(max = 400.dp).heightIn(max = 620.dp), shape = RoundedCornerShape(26.dp), color = Color(0xFA0C0C0C)) {
            Column(Modifier.padding(16.dp)) {
                LaunchedEffect(Unit) { focus.requestFocus(); keyboard?.show() }
                Row(Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(14.dp)).background(FieldBg).padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    FaText("fa-solid fa-magnifying-glass", 13.sp, Dim)
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (q.isEmpty()) Text("Search all apps", fontSize = 14.sp, color = Dim, maxLines = 1)
                        BasicTextField(q, { q = it }, Modifier.fillMaxWidth().focusRequester(focus), singleLine = true,
                            textStyle = TextStyle(color = Ink, fontSize = 14.sp), cursorBrush = SolidColor(Amber),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { shown.firstOrNull()?.let { open(it) } }))
                    }
                }
                LazyColumn(Modifier.padding(top = 8.dp)) {
                    items(shown, key = { it.pkg }) { a ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { open(a) }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            TilePreview(Item("p", "app", a.label, a.pkg, "app"), 38)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(a.label, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, maxLines = 1)
                                Text(a.pkg, fontSize = 10.5.sp, color = Dim, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
}
