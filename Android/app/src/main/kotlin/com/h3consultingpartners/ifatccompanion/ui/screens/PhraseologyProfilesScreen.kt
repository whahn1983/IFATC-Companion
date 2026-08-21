package com.h3consultingpartners.ifatccompanion.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.h3consultingpartners.ifatccompanion.core.phraseology.PhraseologyProfile
import com.h3consultingpartners.ifatccompanion.core.phraseology.PhraseologyTemplate
import com.h3consultingpartners.ifatccompanion.core.phraseology.PhraseologyTemplateKey
import com.h3consultingpartners.ifatccompanion.ui.components.SettingsLink
import com.h3consultingpartners.ifatccompanion.ui.components.SettingsPicker
import com.h3consultingpartners.ifatccompanion.ui.components.SettingsSection

/** The profile list's state, straight from the store. */
data class PhraseologyProfilesModel(
    val profiles: List<PhraseologyProfile>,
    val activeProfileId: String?,
    /** The profile currently open in the editor, or null when the list is showing. */
    val editing: PhraseologyProfile? = null,
    val importFailed: Boolean = false,
)

data class PhraseologyProfilesActions(
    val onSelectActive: (String?) -> Unit,
    val onEdit: (PhraseologyProfile) -> Unit,
    val onCloseEditor: () -> Unit,
    val onSaveDraft: (PhraseologyProfile) -> Unit,
    val onDelete: (PhraseologyProfile) -> Unit,
    val onCreateNew: () -> Unit,
    val onAddExample: () -> Unit,
    val onImportJson: (String) -> Unit,
    val onShareJson: (PhraseologyProfile) -> Unit,
    val onDismissImportError: () -> Unit,
)

/**
 * Phraseology profiles — create, edit, activate, import and export the user's own
 * controller wording and airline radio names. Fully local; profiles share as JSON text.
 *
 * Ported from `IFATCCompanion/Views/PhraseologyProfilesView.swift`. Two SwiftUI
 * affordances have no direct Material 3 equivalent and are replaced rather than
 * approximated: swipe-to-delete behind an `EditButton` becomes an explicit delete icon on
 * each row (discoverable without a mode switch, and the platform-normal shape on Android),
 * and `ShareLink` becomes an Android share sheet raised through [PhraseologyProfilesActions.onShareJson].
 * The screen is navigated as a two-state stack rather than through a NavHost, because it
 * is reached from Settings and has exactly one child.
 */
@Composable
fun PhraseologyProfilesScreen(
    model: PhraseologyProfilesModel,
    actions: PhraseologyProfilesActions,
    modifier: Modifier = Modifier,
) {
    val editing = model.editing
    if (editing != null) {
        PhraseologyProfileEditor(profile = editing, actions = actions, modifier = modifier)
    } else {
        ProfileList(model = model, actions = actions, modifier = modifier)
    }

    if (model.importFailed) {
        AlertDialog(
            onDismissRequest = actions.onDismissImportError,
            confirmButton = {
                TextButton(onClick = actions.onDismissImportError) { Text("OK") }
            },
            title = { Text("Import failed") },
            text = { Text("That text could not be read as a valid phraseology profile.") },
        )
    }
}

// region List

@Composable
private fun ProfileList(
    model: PhraseologyProfilesModel,
    actions: PhraseologyProfilesActions,
    modifier: Modifier,
) {
    var showImport by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item {
            SettingsSection(
                title = "Active profile",
                footer = "The active profile overrides matching controller calls and airline " +
                    "radio names. Built-in uses the selected FAA/ICAO pack only.",
            ) {
                val options = listOf(BuiltInProfile) + model.profiles.map { ProfileOption(it.id, it.name) }
                val selected = options.firstOrNull { it.id == model.activeProfileId } ?: BuiltInProfile
                SettingsPicker(
                    label = "Active profile",
                    selected = selected,
                    options = options,
                    optionTitle = { it.name },
                    onSelect = { actions.onSelectActive(it.id) },
                )
            }
        }

        item {
            SettingsSection(title = "Profiles") {
                if (model.profiles.isEmpty()) {
                    Text(
                        text = "No custom profiles yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 10.dp),
                    )
                }
                for (profile in model.profiles) {
                    ProfileRow(profile = profile, actions = actions)
                }
            }
        }

        item {
            SettingsSection {
                SettingsLink(label = "New Profile", onClick = actions.onCreateNew)
                SettingsLink(label = "Add Example Profile", onClick = actions.onAddExample)
                SettingsLink(label = "Import from JSON", onClick = { showImport = true })
            }
        }
    }

    if (showImport) {
        AlertDialog(
            onDismissRequest = { showImport = false; importText = "" },
            title = { Text("Import Profile") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Paste a profile's exported JSON to add it.")
                    OutlinedTextField(
                        value = importText,
                        onValueChange = { importText = it },
                        placeholder = { Text("Paste profile JSON") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    actions.onImportJson(importText)
                    importText = ""
                    showImport = false
                }) { Text("Import") }
            },
            dismissButton = {
                TextButton(onClick = { showImport = false; importText = "" }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ProfileRow(profile: PhraseologyProfile, actions: PhraseologyProfilesActions) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(profile.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = "${profile.templates.size} template(s), ${profile.airlineCallSets.size} airline(s)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = { actions.onShareJson(profile) }) {
            Icon(Icons.Filled.Share, contentDescription = "Share ${profile.name}")
        }
        IconButton(onClick = { actions.onDelete(profile) }) {
            Icon(Icons.Filled.Delete, contentDescription = "Delete ${profile.name}")
        }
        IconButton(onClick = { actions.onEdit(profile) }) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Edit ${profile.name}")
        }
    }
}

/** The picker's "no profile" entry, which activates the built-in pack. */
private data class ProfileOption(val id: String?, val name: String)

private val BuiltInProfile = ProfileOption(null, "Built-in (none)")

// endregion

// region Editor

/**
 * Edits one profile: its name, its per-call templates, and its airline call set.
 *
 * iOS commits the draft in `onDisappear`. Compose has no equally reliable moment — a
 * process death or a config change would lose it — so every edit writes straight through
 * [PhraseologyProfilesActions.onSaveDraft]. Same result, no window where the work is only
 * in view state.
 */
@Composable
private fun PhraseologyProfileEditor(
    profile: PhraseologyProfile,
    actions: PhraseologyProfilesActions,
    modifier: Modifier,
) {
    var newAirlineKey by remember { mutableStateOf("") }
    var newAirlineName by remember { mutableStateOf("") }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = actions.onCloseEditor) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to profiles")
                }
                Text("Edit Profile", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { actions.onShareJson(profile) }) {
                    Icon(Icons.Filled.Share, contentDescription = "Share this profile")
                }
            }
        }

        item {
            SettingsSection(title = "Name") {
                OutlinedTextField(
                    value = profile.name,
                    onValueChange = { actions.onSaveDraft(profile.copy(name = it)) },
                    placeholder = { Text("Profile name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                )
            }
        }

        item {
            SettingsSection(
                title = "Call Templates",
                footer = "Toggle a call on to override it. Use {placeholder} tokens shown under each field.",
            ) {
                for (key in PhraseologyTemplateKey.entries) {
                    TemplateRow(profile = profile, key = key, actions = actions)
                }
            }
        }

        item {
            SettingsSection(
                title = "Airline Call Set",
                footer = "Map an airline code (used in your flight plan) to its spoken radio " +
                    "name, e.g. DLH → Lufthansa.",
            ) {
                for (code in profile.airlineCallSets.keys.sorted()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(code, style = MaterialTheme.typography.bodyLarge, fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = profile.airlineCallSets[code].orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        IconButton(onClick = {
                            actions.onSaveDraft(profile.copy(airlineCallSets = profile.airlineCallSets - code))
                        }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Remove $code")
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = newAirlineKey,
                        onValueChange = { newAirlineKey = it },
                        placeholder = { Text("Code") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Characters,
                            imeAction = ImeAction.Next,
                        ),
                        modifier = Modifier.width(AIRLINE_CODE_FIELD_WIDTH),
                    )
                    OutlinedTextField(
                        value = newAirlineName,
                        onValueChange = { newAirlineName = it },
                        placeholder = { Text("Spoken name") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = {
                        val code = newAirlineKey.uppercase().trim()
                        val name = newAirlineName.trim()
                        if (code.isEmpty() || name.isEmpty()) return@IconButton
                        actions.onSaveDraft(
                            profile.copy(airlineCallSets = profile.airlineCallSets + (code to name)),
                        )
                        newAirlineKey = ""
                        newAirlineName = ""
                    }) {
                        Icon(Icons.Filled.Add, contentDescription = "Add airline")
                    }
                }
            }
        }
    }
}

@Composable
private fun TemplateRow(
    profile: PhraseologyProfile,
    key: PhraseologyTemplateKey,
    actions: PhraseologyProfilesActions,
) {
    var expanded by remember { mutableStateOf(false) }
    val template: PhraseologyTemplate? = profile.templates[key.rawValue]

    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { expanded = !expanded }) {
                Icon(
                    imageVector = if (expanded) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = if (expanded) "Collapse ${key.title}" else "Expand ${key.title}",
                )
            }
            Text(key.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(
                checked = template != null,
                onCheckedChange = { on ->
                    val next = if (on) {
                        profile.templates + (key.rawValue to key.defaultTemplate)
                    } else {
                        profile.templates - key.rawValue
                    }
                    actions.onSaveDraft(profile.copy(templates = next))
                },
            )
        }

        if (expanded && template != null) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Placeholders: " + key.placeholders.joinToString(" ") { "{$it}" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "Transcript text",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = template.display,
                    onValueChange = { value ->
                        val next = profile.templates + (key.rawValue to template.copy(display = value))
                        actions.onSaveDraft(profile.copy(templates = next))
                    },
                    placeholder = { Text("Display") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "Spoken text",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = template.spoken,
                    onValueChange = { value ->
                        val next = profile.templates + (key.rawValue to template.copy(spoken = value))
                        actions.onSaveDraft(profile.copy(templates = next))
                    },
                    placeholder = { Text("Spoken") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

private val AIRLINE_CODE_FIELD_WIDTH = 110.dp

// endregion
