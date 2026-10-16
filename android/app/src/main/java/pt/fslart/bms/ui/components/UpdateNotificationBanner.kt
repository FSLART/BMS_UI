package pt.fslart.bms.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pt.fslart.bms.ui.theme.*
import pt.fslart.bms.util.AppUpdateManager
import pt.fslart.bms.util.UpdateState

@Composable
fun UpdateNotificationBanner(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val updateState by AppUpdateManager.updateState.collectAsState()

    AnimatedVisibility(
        visible = updateState !is UpdateState.Idle && updateState !is UpdateState.UpToDate,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier
    ) {
        when (val state = updateState) {
            is UpdateState.Available -> {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.5.dp, LartYellow, RoundedCornerShape(12.dp)),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22))
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.SystemUpdate,
                                    contentDescription = "Atualização",
                                    tint = LartYellow,
                                    modifier = Modifier.size(20.dp)
                                )
                                Column {
                                    Text(
                                        text = "NOVA VERSÃO NO GITHUB",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = LartYellow,
                                        letterSpacing = 0.5.sp
                                    )
                                    Text(
                                        text = "Versão ${state.info.versionName} Disponível",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = DarkTextPrimary
                                    )
                                }
                            }

                            IconButton(
                                onClick = { AppUpdateManager.dismiss() },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Fechar",
                                    tint = DarkTextMuted,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        if (!state.info.changelog.isNullOrBlank()) {
                            Text(
                                text = state.info.changelog,
                                fontSize = 11.sp,
                                color = DarkTextSecondary,
                                lineHeight = 15.sp
                            )
                        }

                        Button(
                            onClick = {
                                AppUpdateManager.startDownloadAndInstall(context, coroutineScope, state.info)
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = LartYellow,
                                contentColor = Color.Black
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = "Descarregar",
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Descarregar e Atualizar Agora",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }

            is UpdateState.Downloading -> {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.dp, LartYellow.copy(alpha = 0.5f), RoundedCornerShape(12.dp)),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22))
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = LartYellow
                                )
                                Text(
                                    text = "A transferir nova versão do GitHub...",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = DarkTextPrimary
                                )
                            }

                            Text(
                                text = "${(state.progress * 100).toInt()}%",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = LartYellow,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        LinearProgressIndicator(
                            progress = { state.progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = LartYellow,
                            trackColor = DarkSurfaceElevated
                        )

                        if (state.totalBytes > 0) {
                            val downloadedMb = state.downloadedBytes / (1024f * 1024f)
                            val totalMb = state.totalBytes / (1024f * 1024f)
                            Text(
                                text = "%.1f MB de %.1f MB".format(downloadedMb, totalMb),
                                fontSize = 10.sp,
                                color = DarkTextMuted,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }

            is UpdateState.ReadyToInstall -> {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.5.dp, StatusOkGlow, RoundedCornerShape(12.dp)),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0E2E1A))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "DOWNLOAD CONCLUÍDO",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = StatusOkGlow
                            )
                            Text(
                                text = "Toca para abrir o instalador",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }

                        Button(
                            onClick = { AppUpdateManager.installApk(context, state.file) },
                            colors = ButtonDefaults.buttonColors(containerColor = StatusOkGlow, contentColor = Color.Black),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Instalar", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }
            }

            is UpdateState.Error -> {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .border(1.dp, StatusFault.copy(alpha = 0.5f), RoundedCornerShape(10.dp)),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2E1010))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = state.message,
                            fontSize = 11.sp,
                            color = StatusFault,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { AppUpdateManager.dismiss() },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Fechar", tint = DarkTextMuted, modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }

            else -> Unit
        }
    }
}
