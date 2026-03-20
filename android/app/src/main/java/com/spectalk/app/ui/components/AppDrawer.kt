package com.spectalk.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.spectalk.app.navigation.Screen

@Composable
fun AppDrawer(
    currentRoute: String?,
    userEmail: String,
    onNavigateToHome: () -> Unit,
    onNavigateToGallery: () -> Unit,
    onNavigateToTutorial: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onSignOut: () -> Unit,
) {
    ModalDrawerSheet(
        drawerContainerColor = MaterialTheme.colorScheme.background,
        drawerShape = RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 14.dp, vertical = 18.dp),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                DrawerHeader(userEmail = userEmail)

                Spacer(Modifier.height(18.dp))

                Text(
                    text = "Workspace",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.52f),
                    modifier = Modifier.padding(horizontal = 10.dp),
                )

                Spacer(Modifier.height(10.dp))

                DrawerDestination(
                    label = "Conversations",
                    icon = { Icon(Icons.Rounded.Mic, contentDescription = null) },
                    selected = currentRoute == Screen.Home.route,
                    onClick = onNavigateToHome,
                    supportingText = "Talk to Gervis and review recent work",
                )

                Spacer(Modifier.height(8.dp))

                DrawerDestination(
                    label = "Gallery",
                    icon = { Icon(Icons.Filled.PhotoLibrary, contentDescription = null) },
                    selected = currentRoute == Screen.Gallery.route,
                    onClick = onNavigateToGallery,
                    supportingText = "Captured images from glasses and phone",
                )

                Spacer(Modifier.height(8.dp))

                DrawerDestination(
                    label = "Tutorial",
                    icon = { Icon(Icons.Filled.Info, contentDescription = null) },
                    selected = currentRoute == Screen.Tutorial.route,
                    onClick = onNavigateToTutorial,
                    supportingText = "Quick guide for talking, typing, and images",
                )

                Spacer(Modifier.height(16.dp))

                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 10.dp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                )

                Spacer(Modifier.height(16.dp))

                Text(
                    text = "Preferences",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.52f),
                    modifier = Modifier.padding(horizontal = 10.dp),
                )

                Spacer(Modifier.height(10.dp))

                DrawerDestination(
                    label = "Settings",
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    selected = currentRoute == Screen.Settings.route,
                    onClick = onNavigateToSettings,
                    supportingText = "Audio routes, notifications, and devices",
                )

                Spacer(Modifier.weight(1f))

                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 10.dp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                )

                Spacer(Modifier.height(12.dp))

                NavigationDrawerItem(
                    icon = {
                        Icon(
                            Icons.AutoMirrored.Filled.Logout,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    },
                    label = {
                        Text(
                            text = "Sign out",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                    },
                    selected = false,
                    onClick = onSignOut,
                    modifier = Modifier.padding(horizontal = 2.dp),
                    colors = NavigationDrawerItemDefaults.colors(
                        unselectedContainerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.06f),
                        unselectedTextColor = MaterialTheme.colorScheme.error,
                        unselectedIconColor = MaterialTheme.colorScheme.error,
                    ),
                )
            }
        }
    }
}

@Composable
private fun DrawerHeader(userEmail: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f),
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
                    ),
                ),
            )
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                shape = RoundedCornerShape(26.dp),
            )
            .padding(18.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Mic,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "SpecTalk",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "Voice-first creative workspace",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.64f),
                    )
                }
            }

            StatePill(text = "Gervis ready")

            if (userEmail.isNotBlank()) {
                Text(
                    text = userEmail,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.52f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun DrawerDestination(
    label: String,
    icon: @Composable () -> Unit,
    selected: Boolean,
    onClick: () -> Unit,
    supportingText: String,
) {
    NavigationDrawerItem(
        icon = icon,
        label = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = supportingText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.48f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        selected = selected,
        onClick = onClick,
        modifier = Modifier.padding(horizontal = 2.dp),
        colors = NavigationDrawerItemDefaults.colors(
            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
            selectedTextColor = MaterialTheme.colorScheme.primary,
            selectedIconColor = MaterialTheme.colorScheme.primary,
            unselectedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
            unselectedTextColor = MaterialTheme.colorScheme.onSurface,
            unselectedIconColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
        ),
    )
}

@Composable
private fun StatePill(text: String) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
