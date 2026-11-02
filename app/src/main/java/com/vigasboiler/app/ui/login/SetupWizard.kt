package com.vigasboiler.app.ui.login

import android.content.ClipData
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.browser.auth.AuthTabIntent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import com.vigasboiler.app.R
import com.vigasboiler.app.auth.ClientId
import com.vigasboiler.app.ui.common.AppCard
import com.vigasboiler.app.ui.common.IconBadge

const val PORTAL_URL = "https://developer.viessmann-climatesolutions.com"

private enum class Step { Welcome, Account, Client, ClientId, SignIn }

/**
 * First start: explains the developer portal, hands out the fixed redirect URI, takes the
 * user's client ID and signs in. Users who already entered a client ID start at sign-in.
 */
@Composable
fun SetupWizard(viewModel: LoginViewModel, sessionExpired: Boolean) {
    val client by viewModel.client.collectAsStateWithLifecycle()
    var step by rememberSaveable { mutableIntStateOf(if (client.clientId != null) Step.SignIn.ordinal else Step.Welcome.ordinal) }
    val current = Step.entries[step]
    BackHandler(enabled = current != Step.Welcome) { step-- }

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding()) {
            if (current != Step.Welcome) {
                StepProgress(current.ordinal, Step.entries.size - 1)
            }
            AnimatedContent(
                targetState = current,
                transitionSpec = {
                    val dir = if (targetState > initialState) 1 else -1
                    (slideInHorizontally { it / 4 * dir } + fadeIn()) togetherWith (slideOutHorizontally { -it / 4 * dir } + fadeOut())
                },
                modifier = Modifier.weight(1f),
                label = "setup",
            ) { s ->
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    when (s) {
                        Step.Welcome -> Welcome(onStart = { step = Step.Account.ordinal }, onHaveClient = { step = Step.ClientId.ordinal })
                        Step.Account -> AccountStep()
                        Step.Client -> ClientStep(viewModel.redirectUri)
                        Step.ClientId -> ClientIdStep(client.clientId, onSave = { input ->
                            viewModel.saveClientId(input).also { ok -> if (ok) step = Step.SignIn.ordinal }
                        })
                        Step.SignIn -> SignInStep(viewModel, client.clientId, sessionExpired, onChange = { step = Step.ClientId.ordinal })
                    }
                }
            }
            if (current == Step.Account || current == Step.Client) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { step-- }) { Text(stringResource(R.string.setup_back)) }
                    Button(onClick = { step++ }) { Text(stringResource(R.string.setup_next)) }
                }
            }
        }
    }
}

@Composable
private fun StepProgress(step: Int, of: Int) {
    Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.setup_step_of, step, of), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LinearProgressIndicator(progress = { step / of.toFloat() }, Modifier.fillMaxWidth())
    }
}

@Composable
private fun ColumnScope.Welcome(onStart: () -> Unit, onHaveClient: () -> Unit) {
    Spacer(Modifier.height(24.dp))
    IconBadge(R.drawable.ic_nav_heating, MaterialTheme.colorScheme.primary, Modifier.align(Alignment.CenterHorizontally), size = 88)
    Text(stringResource(R.string.app_name), Modifier.fillMaxWidth(), style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center)
    Text(stringResource(R.string.setup_tagline), Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    AppCard {
        Text(stringResource(R.string.setup_need_title), style = MaterialTheme.typography.titleMedium)
        NeedRow(R.drawable.ic_boiler, stringResource(R.string.setup_need_boiler))
        NeedRow(R.drawable.ic_person, stringResource(R.string.setup_need_account))
        NeedRow(R.drawable.ic_key, stringResource(R.string.setup_need_portal))
    }
    Text(stringResource(R.string.setup_own_client), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.weight(1f))
    Button(onClick = onStart, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(stringResource(R.string.setup_start), style = MaterialTheme.typography.titleMedium) }
    TextButton(onClick = onHaveClient, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.setup_have_client)) }
}

@Composable
private fun NeedRow(@DrawableRes icon: Int, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        IconBadge(icon, MaterialTheme.colorScheme.primary, size = 32)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun AccountStep() {
    val uriHandler = LocalUriHandler.current
    Title(stringResource(R.string.setup_account_title))
    Numbered(1, stringResource(R.string.setup_account_1)) {
        OutlinedButton(onClick = { uriHandler.openUri(PORTAL_URL) }) { ButtonIcon(R.drawable.ic_open); Text(stringResource(R.string.setup_open_portal)) }
    }
    Numbered(2, stringResource(R.string.setup_account_2))
    Numbered(3, stringResource(R.string.setup_account_3))
    Note(stringResource(R.string.setup_account_note))
}

@Composable
private fun ClientStep(redirectUri: String) {
    val uriHandler = LocalUriHandler.current
    Title(stringResource(R.string.setup_client_title))
    Numbered(1, stringResource(R.string.setup_client_1)) {
        OutlinedButton(onClick = { uriHandler.openUri(PORTAL_URL) }) { ButtonIcon(R.drawable.ic_open); Text(stringResource(R.string.setup_open_portal)) }
    }
    Numbered(2, stringResource(R.string.setup_client_2))
    Numbered(3, stringResource(R.string.setup_client_3))
    Numbered(4, stringResource(R.string.setup_client_4)) { CopyField(redirectUri) }
    Numbered(5, stringResource(R.string.setup_client_5))
}

@Composable
private fun ClientIdStep(saved: String?, onSave: (String) -> Boolean) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var text by rememberSaveable { mutableStateOf(saved.orEmpty()) }
    var tried by rememberSaveable { mutableStateOf(false) }
    val valid = ClientId.isValid(ClientId.normalize(text))
    val showError = tried && !valid
    Title(stringResource(R.string.setup_id_title))
    Text(stringResource(R.string.setup_id_intro), style = MaterialTheme.typography.bodyLarge)
    OutlinedTextField(
        value = text,
        onValueChange = { text = it; tried = false },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.setup_id_label)) },
        singleLine = true,
        isError = showError,
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
        supportingText = if (showError) ({ Text(stringResource(R.string.setup_id_invalid)) }) else null,
        trailingIcon = {
            IconButton(onClick = {
                scope.launch {
                    clipboard.getClipEntry()?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.let { text = it.toString(); tried = true }
                }
            }) { Icon(painterResource(R.drawable.ic_paste), stringResource(R.string.setup_id_paste)) }
        },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { tried = true; onSave(text) }),
    )
    Note(stringResource(R.string.setup_id_note))
    Button(onClick = { tried = true; onSave(text) }, enabled = text.isNotBlank(), modifier = Modifier.fillMaxWidth().height(52.dp)) {
        Text(stringResource(R.string.setup_next), style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun ColumnScope.SignInStep(viewModel: LoginViewModel, clientId: String?, sessionExpired: Boolean, onChange: () -> Unit) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val authTab = rememberLauncherForActivityResult(AuthTabIntent.AuthenticateUserResultContract(), viewModel::onAuthTabResult)
    val customTab = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        viewModel.onAuthorizationResult(result.data)
    }
    Title(stringResource(R.string.setup_signin_title))
    Text(stringResource(R.string.setup_signin_intro), style = MaterialTheme.typography.bodyLarge)
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.setup_client_in_use), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(clientId.orEmpty(), style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
            }
            TextButton(onClick = onChange) { Text(stringResource(R.string.setup_change)) }
        }
    }
    if (sessionExpired) Text(stringResource(R.string.login_session_expired), color = MaterialTheme.colorScheme.error)
    ui.error?.let { Text(stringResource(R.string.login_failed, it), color = MaterialTheme.colorScheme.error) }
    if (ui.cancelled) Text(stringResource(R.string.login_cancelled))
    if (ui.inProgress) {
        CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
    } else {
        Button(
            onClick = {
                when (val launch = viewModel.startSignIn()) {
                    is SignInLaunch.AuthTab -> AuthTabIntent.Builder().build().launch(authTab, launch.uri, launch.redirectScheme)
                    is SignInLaunch.CustomTab -> customTab.launch(launch.intent)
                    null -> Unit
                }
            },
            enabled = clientId != null,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text(stringResource(R.string.login_button), style = MaterialTheme.typography.titleMedium) }
    }
    if (ui.error != null || ui.cancelled) {
        AppCard {
            Text(stringResource(R.string.setup_trouble_title), style = MaterialTheme.typography.titleSmall)
            Html(stringResource(R.string.setup_trouble_redirect, viewModel.redirectUri))
            Html(stringResource(R.string.setup_trouble_client))
            Html(stringResource(R.string.setup_trouble_captcha))
        }
    }
}

@Composable
private fun Title(text: String) {
    Text(text, style = MaterialTheme.typography.headlineMedium)
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Html(text: String) {
    Text(AnnotatedString.fromHtml(text), style = MaterialTheme.typography.bodyMedium)
}

/** Numbered instruction with optional content (button, copy field) underneath. */
@Composable
private fun Numbered(n: Int, html: String, extra: @Composable ColumnScope.() -> Unit = {}) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Surface(Modifier.size(28.dp), shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
            Box(contentAlignment = Alignment.Center) {
                Text("$n", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
        Column(Modifier.weight(1f).padding(top = 3.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(AnnotatedString.fromHtml(html), style = MaterialTheme.typography.bodyLarge)
            extra()
        }
    }
}

/** Monospace value with a copy button. */
@Composable
private fun CopyField(value: String) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by rememberSaveable { mutableStateOf(false) }
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
            TextButton(onClick = {
                scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Redirect URI", value))) }
                copied = true
            }) {
                ButtonIcon(if (copied) R.drawable.ic_check else R.drawable.ic_copy)
                Text(stringResource(if (copied) R.string.setup_copied else R.string.setup_copy))
            }
        }
    }
}

@Composable
private fun ButtonIcon(@DrawableRes icon: Int) {
    Icon(painterResource(icon), null, Modifier.size(18.dp))
    Spacer(Modifier.width(8.dp))
}
