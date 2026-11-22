package ru.jbosint.osint.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ru.jbosint.osint.data.DocHit
import ru.jbosint.osint.data.DocumentText
import ru.jbosint.osint.data.DocRecord

/**
 * «Документы»: импорт файла в приватное досье, полнотекстовый поиск.
 * Текст извлекается локально; PDF и офисные форматы — через серверный MarkItDown,
 * если сервер включён. Сами файлы никуда не отправляются, кроме извлечения текста.
 */
@Composable
fun DocumentsCard(state: UiState, vm: AppViewModel) {
    GlowCard(modifier = Modifier.fillMaxWidth(), padding = 16) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("ДОКУМЕНТЫ", style = MaterialTheme.typography.labelSmall, color = JbFaint)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Поиск по своим файлам",
                    style = MaterialTheme.typography.titleMedium,
                    color = JbText
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "PDF, DOCX, TXT — текст ищется офлайн, файлы остаются на телефоне",
                    style = MaterialTheme.typography.bodySmall,
                    color = JbMuted
                )
            }
            Spacer(Modifier.width(10.dp))
            TextButton(onClick = vm::openDocs) {
                Text(
                    if (state.docs.items.isEmpty()) "Открыть" else "${state.docs.items.size}",
                    color = JbAccent2
                )
            }
        }
    }
}

@Composable
fun DocumentsDialog(state: UiState, vm: AppViewModel) {
    val docs = state.docs
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importDocument(uri)
    }
    Dialog(
        onDismissRequest = vm::closeDocs,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(color = JbBg, modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("ДОКУМЕНТЫ", style = MaterialTheme.typography.labelSmall, color = JbAccent2)
                    Spacer(Modifier.weight(1f))
                    if (docs.items.isNotEmpty()) {
                        Pill("${docs.items.size}", JbFaint)
                        Spacer(Modifier.width(6.dp))
                    }
                    IconButton(onClick = vm::closeDocs) {
                        Icon(Icons.Filled.Close, "Закрыть", tint = JbMuted)
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = {
                            picker.launch(
                                arrayOf(
                                    "application/pdf",
                                    "text/*",
                                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                    "application/msword",
                                    "application/octet-stream"
                                )
                            )
                        },
                        enabled = !docs.busy
                    ) {
                        Text(if (docs.busy) "Читаю…" else "Импортировать файл")
                    }
                    if (docs.busy) {
                        Spacer(Modifier.width(10.dp))
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = JbAccent2
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = docs.query,
                    onValueChange = vm::setDocsQuery,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Поиск по тексту документов") },
                    textStyle = MaterialTheme.typography.bodyMedium
                )

                docs.note?.let { note ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        note,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (note.startsWith("Добавлено")) JbGreen else JbAmber
                    )
                }
                Spacer(Modifier.height(12.dp))

                val listModifier = Modifier.weight(1f).fillMaxWidth()
                when {
                    docs.busy && docs.items.isEmpty() -> Text(
                        "Извлекаю текст…",
                        style = MaterialTheme.typography.bodySmall,
                        color = JbMuted
                    )

                    docs.query.isNotBlank() && docs.hits.isEmpty() -> Column {
                        Text(
                            "Совпадений нет",
                            style = MaterialTheme.typography.bodySmall,
                            color = JbMuted
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Поиск идёт по извлечённому тексту и требует всех слов запроса.",
                            style = MaterialTheme.typography.labelSmall,
                            color = JbFaint
                        )
                    }

                    docs.query.isNotBlank() -> LazyColumn(
                        modifier = listModifier,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(docs.hits, key = { it.doc.id }) { hit -> DocHitRow(hit) }
                    }

                    docs.items.isEmpty() -> Column {
                        Text(
                            "Пока пусто",
                            style = MaterialTheme.typography.bodySmall,
                            color = JbMuted
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Импортируйте PDF, DOCX или текстовый файл. TXT, MD, CSV, HTML и DOCX " +
                                "разбираются на телефоне без сети; PDF — через серверный режим.",
                            style = MaterialTheme.typography.labelSmall,
                            color = JbFaint
                        )
                    }

                    else -> LazyColumn(
                        modifier = listModifier,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(docs.items, key = { it.id }) { doc ->
                            DocRow(doc, onDelete = { vm.deleteDocument(doc.id) })
                        }
                        item {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Текст хранится в приватной папке приложения. Удаление документа " +
                                    "удаляет и текст.",
                                style = MaterialTheme.typography.labelSmall,
                                color = JbFaint
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DocRow(doc: DocRecord, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(JbSurface)
            .border(1.dp, JbBorderSoft, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                doc.title.ifBlank { doc.fileName },
                style = MaterialTheme.typography.bodyMedium,
                color = JbText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                buildString {
                    append(doc.fileName)
                    append(" · ")
                    append("${doc.bytes / 1024} КБ")
                    if (doc.chars > 0) append(" · ${doc.chars} симв.")
                },
                style = MaterialTheme.typography.labelSmall,
                color = JbFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (doc.note.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    doc.note,
                    style = MaterialTheme.typography.labelSmall,
                    color = JbAmber
                )
            }
        }
        Spacer(Modifier.width(6.dp))
        Text(
            DocumentText.providerTag(doc.provider),
            style = MaterialTheme.typography.labelSmall,
            color = JbFaint
        )
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.DeleteOutline, "Удалить", tint = JbMuted)
        }
    }
}

@Composable
private fun DocHitRow(hit: DocHit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(JbSurface)
            .border(1.dp, JbBorderSoft, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                hit.doc.title.ifBlank { hit.doc.fileName },
                style = MaterialTheme.typography.bodyMedium,
                color = JbText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Pill("${hit.score}", JbAccent2)
        }
        Spacer(Modifier.height(4.dp))
        Text(hit.snippet, style = MaterialTheme.typography.bodySmall, color = JbMuted)
    }
}
