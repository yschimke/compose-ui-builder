// Generator content SHA-256: 7da9a2d9b7009d0a565a5830c96da3f9d29f039a80d8c7b64880912030e5646e
@file:OptIn(ExperimentalMaterial3Api::class)

package generated.uibuilder

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.uibuilder.artwork.ProjectOwnedJetcasterArtwork

// Generated from design fixture-jetcaster-discover-expanded revision 99.
// Catalog m3-catalog@candidate; capability candidate.
// TODO[ADAPTIVE_COMPATIBILITY_HELPER] node=pane-scaffold: two-pane helper does not prove adaptive
// posture or motion parity
// TODO[UNEMITTED_EVENT] node=podcast-card-android: event 'click' is not emitted
// TODO[CAROUSEL_COMPATIBILITY_HELPER] node=podcast-carousel: row helper preserves order and sizing
// but not Material carousel masking
@Composable
fun JetcasterDiscoverExpandedSupportingPane() {
  var searchQuery by remember { mutableStateOf("") }
  var selectedCategory by remember { mutableStateOf("Crime") }
  var selectedDestination by remember { mutableStateOf("Discover") }
  var selectedPodcast by remember { mutableStateOf("android-developers-backstage") }
  // node:root-surface component:m3/surface symbol:Surface
  // typed-properties:{"containerColor":{"type":"colorToken","value":"background"}}
  Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    // node:pane-scaffold component:layout/supporting-pane-scaffold symbol:SupportingPaneScaffold
    // typed-properties:{"layoutMode":{"type":"enum","value":"expandedTwoPane"},"mainPanePreferredWidthDp":{"type":"float","value":744.0},"supportingPanePreferredWidthDp":{"type":"float","value":512.0},"paneSpacingDp":{"type":"float","value":24.0},"mainPaneVisible":{"type":"bool","value":true},"supportingPaneVisible":{"type":"bool","value":true}}
    BuilderSupportingPaneScaffold(
      modifier = Modifier.fillMaxSize(),
      mainPaneWidth = 744.dp,
      supportingPaneWidth = 512.dp,
      paneSpacing = 24.dp,
      layoutMode = "expandedTwoPane",
      mainPaneVisible = true,
      supportingPaneVisible = true,
      mainPane = {
        // node:main-background component:layout/box symbol:Box
        // typed-properties:{}
        Box(modifier = Modifier.fillMaxSize()) {
          // node:main-scrim component:shape/radial-gradient
          // symbol:Modifier.background(Brush.radialGradient)
          // typed-properties:{"center":{"type":"enum","value":"topStart"},"innerColor":{"type":"colorToken","value":"primary"},"innerAlpha":{"type":"float","value":0.15},"outerColor":{"type":"colorToken","value":"transparent"}}
          BuilderRadialGradient(
            Modifier.matchParentSize(),
            MaterialTheme.colorScheme.primary,
            0.15f,
            Color.Transparent,
            Offset.Zero,
          )
          // node:main-scaffold component:layout/scaffold symbol:Scaffold
          // typed-properties:{"containerColor":{"type":"colorToken","value":"transparent"},"loading":{"type":"bool","value":false}}
          Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = Color.Transparent,
            topBar = {
              // node:search-bar component:m3/search-bar symbol:SearchBar
              // typed-properties:{"expanded":{"type":"bool","value":false},"tonalElevationDp":{"type":"float","value":0.0}}
              BuilderSearchBar(
                expanded = false,
                tonalElevation = 0.dp,
                modifier =
                  Modifier.fillMaxWidth()
                    .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 8.dp),
              ) {
                // node:search-input component:m3/search-input-field
                // symbol:SearchBarDefaults.InputField
                // typed-properties:{"value":{"type":"state","variable":"searchQuery"},"enabled":{"type":"bool","value":true}}
                BuilderSearchInputField(
                  value = searchQuery,
                  onValueChange = { searchQuery = it },
                  enabled = true,
                  leadingIcon = {
                    // node:search-leading-icon component:m3/icon symbol:Icon
                    // typed-properties:{"iconKey":{"type":"enum","value":"search"},"contentDescription":{"type":"string","value":"Search"}}
                    Icon(
                      imageVector = builderIcon("search"),
                      contentDescription = "Search",
                      tint = Color.Unspecified,
                      modifier = Modifier.semantics { contentDescription = "Search" },
                    )
                  },
                  placeholder = {
                    // node:search-placeholder component:m3/text symbol:Text
                    // typed-properties:{"text":{"type":"string","value":"Search for a
                    // podcast"},"style":{"type":"typographyToken","value":"bodyLarge"},"color":{"type":"colorToken","value":"onSurfaceVariant"}}
                    Text(
                      text = "Search for a podcast",
                      style = MaterialTheme.typography.bodyLarge,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                      maxLines = 2147483647,
                      overflow = TextOverflow.Ellipsis,
                      modifier = Modifier,
                    )
                  },
                  trailingIcon = {
                    // node:search-account-icon component:m3/icon symbol:Icon
                    // typed-properties:{"iconKey":{"type":"enum","value":"accountCircle"},"contentDescription":{"type":"string","value":"Account"}}
                    Icon(
                      imageVector = builderIcon("accountCircle"),
                      contentDescription = "Account",
                      tint = Color.Unspecified,
                      modifier = Modifier.semantics { contentDescription = "Account" },
                    )
                  },
                )
              }
            },
            snackbarHost = {
              // node:snackbar-host component:m3/snackbar-host symbol:SnackbarHost
              // typed-properties:{"visible":{"type":"bool","value":false}}
              BuilderSnackbarHost(visible = false)
            },
          ) { contentPadding ->
            Box(Modifier.padding(contentPadding)) {
              // node:main-content component:layout/box symbol:Box
              // typed-properties:{}
              Box(modifier = Modifier.fillMaxSize()) {
                key("discover-main-scroll") {
                  // node:discover-grid component:layout/lazy-grid symbol:LazyVerticalGrid
                  // typed-properties:{"columns":{"type":"adaptiveGrid","minimumCellWidthDp":362.0},"contentPadding":{"type":"padding","startDp":0,"topDp":0,"endDp":0,"bottomDp":88},"scrollStateKey":{"type":"string","value":"discover-main-scroll"}}
                  LazyVerticalGrid(
                    columns = GridCells.Adaptive(362.dp),
                    contentPadding =
                      PaddingValues(start = 0.dp, top = 0.dp, end = 0.dp, bottom = 88.dp),
                    modifier = Modifier.fillMaxSize(),
                  ) {
                    item(key = "categories", span = { GridItemSpan(maxLineSpan) }) {
                      key("categories") {
                        // node:category-row component:layout/lazy-row symbol:LazyRow
                        // typed-properties:{"span":{"type":"enum","value":"full"},"stableKey":{"type":"string","value":"categories"},"contentPadding":{"type":"padding","startDp":16,"topDp":16,"endDp":16,"bottomDp":16},"horizontalSpacingDp":{"type":"float","value":8.0}}
                        LazyRow(
                          contentPadding =
                            PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 16.dp),
                          horizontalArrangement = Arrangement.spacedBy(8.dp),
                          modifier = Modifier,
                        ) {
                          item(key = "chip-crime") {
                            // node:chip-crime component:m3/filter-chip symbol:FilterChip
                            // typed-properties:{"selected":{"type":"stateEquals","variable":"selectedCategory","value":"Crime"},"shape":{"type":"shapeToken","value":"large"}}
                            FilterChip(
                              selected = selectedCategory == "Crime",
                              onClick = { selectedCategory = "Crime" },
                              enabled = true,
                              shape = RoundedCornerShape(16.dp),
                              label = {
                                // node:chip-crime-label component:m3/text symbol:Text
                                // typed-properties:{"text":{"type":"string","value":"Crime"},"style":{"type":"typographyToken","value":"bodyMedium"}}
                                Text(
                                  text = "Crime",
                                  style = MaterialTheme.typography.bodyMedium,
                                  color = Color.Unspecified,
                                  maxLines = 2147483647,
                                  overflow = TextOverflow.Ellipsis,
                                  modifier = Modifier,
                                )
                              },
                              leadingIcon = {
                                // node:chip-crime-check component:m3/icon symbol:Icon
                                // typed-properties:{"iconKey":{"type":"enum","value":"check"},"sizeDp":{"type":"float","value":18.0}}
                                Icon(
                                  imageVector = builderIcon("check"),
                                  contentDescription = null,
                                  tint = Color.Unspecified,
                                  modifier = Modifier.size(18.dp),
                                )
                              },
                            )
                          }
                          item(key = "chip-news") {
                            // node:chip-news component:m3/filter-chip symbol:FilterChip
                            // typed-properties:{"selected":{"type":"stateEquals","variable":"selectedCategory","value":"News"},"shape":{"type":"shapeToken","value":"large"}}
                            FilterChip(
                              selected = selectedCategory == "News",
                              onClick = { selectedCategory = "News" },
                              enabled = true,
                              shape = RoundedCornerShape(16.dp),
                              label = {
                                // node:chip-news-label component:m3/text symbol:Text
                                // typed-properties:{"text":{"type":"string","value":"News"},"style":{"type":"typographyToken","value":"bodyMedium"}}
                                Text(
                                  text = "News",
                                  style = MaterialTheme.typography.bodyMedium,
                                  color = Color.Unspecified,
                                  maxLines = 2147483647,
                                  overflow = TextOverflow.Ellipsis,
                                  modifier = Modifier,
                                )
                              },
                            )
                          }
                          item(key = "chip-comedy") {
                            // node:chip-comedy component:m3/filter-chip symbol:FilterChip
                            // typed-properties:{"selected":{"type":"stateEquals","variable":"selectedCategory","value":"Comedy"},"shape":{"type":"shapeToken","value":"large"}}
                            FilterChip(
                              selected = selectedCategory == "Comedy",
                              onClick = { selectedCategory = "Comedy" },
                              enabled = true,
                              shape = RoundedCornerShape(16.dp),
                              label = {
                                // node:chip-comedy-label component:m3/text symbol:Text
                                // typed-properties:{"text":{"type":"string","value":"Comedy"},"style":{"type":"typographyToken","value":"bodyMedium"}}
                                Text(
                                  text = "Comedy",
                                  style = MaterialTheme.typography.bodyMedium,
                                  color = Color.Unspecified,
                                  maxLines = 2147483647,
                                  overflow = TextOverflow.Ellipsis,
                                  modifier = Modifier,
                                )
                              },
                            )
                          }
                        }
                      }
                    }
                    item(key = "podcast-carousel", span = { GridItemSpan(maxLineSpan) }) {
                      key("discover-podcast-carousel") {
                        // node:podcast-carousel component:layout/horizontal-carousel
                        // symbol:HorizontalUncontainedCarousel
                        // typed-properties:{"kind":{"type":"enum","value":"uncontained"},"span":{"type":"enum","value":"full"},"itemWidthDp":{"type":"float","value":128.0},"itemSpacingDp":{"type":"float","value":4.0},"contentPaddingStartDp":{"type":"float","value":8.0},"scrollStateKey":{"type":"string","value":"discover-podcast-carousel"}}
                        BuilderHorizontalCarousel(
                          kind = "uncontained",
                          itemWidth = 128.dp,
                          spacing = 4.dp,
                          contentPaddingStart = 8.dp,
                        ) { itemWidth ->
                          Box(Modifier.width(itemWidth)) {
                            key("android-developers-backstage") {
                              // node:podcast-card-android component:m3/card symbol:Card
                              // typed-properties:{"stableKey":{"type":"string","value":"android-developers-backstage"},"shape":{"type":"shapeToken","value":"large"}}
                              Card(
                                modifier =
                                  Modifier.size(width = 128.dp, height = 128.dp)
                                    .clip(RoundedCornerShape(16.dp)),
                                shape = RoundedCornerShape(16.dp),
                                colors = builderCardColors(Color.Unspecified),
                              ) {
                                Box(Modifier.fillMaxSize()) {
                                  // node:podcast-card-android-image component:asset/image
                                  // symbol:Image
                                  // typed-properties:{"assetKey":{"type":"assetKey","value":"jetcaster.cover.android-developers-backstage"},"contentDescription":{"type":"string","value":"Android Developers Backstage cover"},"contentScale":{"type":"enum","value":"crop"}}
                                  BuilderAssetImage(
                                    assetKey = "jetcaster.cover.android-developers-backstage",
                                    contentDescription = "Android Developers Backstage cover",
                                    contentScale = "crop",
                                    modifier =
                                      Modifier.matchParentSize().semantics {
                                        contentDescription = "Android Developers Backstage cover"
                                      },
                                  )
                                  // node:podcast-card-android-gradient
                                  // component:shape/linear-gradient
                                  // symbol:Modifier.background(Brush.linearGradient)
                                  // typed-properties:{"direction":{"type":"enum","value":"topToBottom"},"startColor":{"type":"color","value":"#00000000"},"endColor":{"type":"color","value":"#FF000000"}}
                                  Box(
                                    Modifier.matchParentSize()
                                      .background(
                                        Brush.verticalGradient(
                                          listOf(Color(0x00000000), Color(0xFF000000))
                                        )
                                      )
                                  )
                                  // node:podcast-card-android-follow component:m3/icon-button
                                  // symbol:IconButton
                                  // typed-properties:{"alignment":{"type":"enum","value":"topStart"},"selected":{"type":"bool","value":true},"contentDescription":{"type":"string","value":"Unfollow Android Developers Backstage"}}
                                  IconButton(
                                    onClick = {},
                                    modifier =
                                      Modifier.align(Alignment.TopStart)
                                        .semantics {
                                          contentDescription =
                                            "Unfollow Android Developers Backstage"
                                        }
                                        .semantics { selected = true },
                                  ) {
                                    // node:podcast-card-android-follow-icon component:m3/icon
                                    // symbol:Icon
                                    // typed-properties:{"iconKey":{"type":"enum","value":"checkCircle"},"color":{"type":"colorToken","value":"primary"}}
                                    Icon(
                                      imageVector = builderIcon("checkCircle"),
                                      contentDescription = null,
                                      tint = MaterialTheme.colorScheme.primary,
                                      modifier = Modifier,
                                    )
                                  }
                                  // node:podcast-card-android-title component:m3/text symbol:Text
                                  // typed-properties:{"text":{"type":"string","value":"Android
                                  // Developers
                                  // Backstage"},"style":{"type":"typographyToken","value":"bodyMedium"},"color":{"type":"color","value":"#FFFFFFFF"},"maxLines":{"type":"int","value":2},"overflow":{"type":"enum","value":"ellipsis"},"alignment":{"type":"enum","value":"bottomStart"}}
                                  Text(
                                    text = "Android Developers Backstage",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color(0xFFFFFFFF),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier =
                                      Modifier.padding(
                                          start = 16.dp,
                                          top = 0.dp,
                                          end = 12.dp,
                                          bottom = 16.dp,
                                        )
                                        .align(Alignment.BottomStart),
                                  )
                                }
                              }
                            }
                          }
                          Box(Modifier.width(itemWidth)) {
                            key("google-developers-podcast") {
                              // node:podcast-card-google component:m3/card symbol:Card
                              // typed-properties:{"stableKey":{"type":"string","value":"google-developers-podcast"},"shape":{"type":"shapeToken","value":"large"}}
                              Card(
                                modifier =
                                  Modifier.size(width = 128.dp, height = 128.dp)
                                    .clip(RoundedCornerShape(16.dp)),
                                shape = RoundedCornerShape(16.dp),
                                colors = builderCardColors(Color.Unspecified),
                              ) {
                                Box(Modifier.fillMaxSize()) {
                                  // node:podcast-card-google-image component:asset/image
                                  // symbol:Image
                                  // typed-properties:{"assetKey":{"type":"assetKey","value":"jetcaster.cover.google-developers-podcast"},"contentDescription":{"type":"string","value":"Google Developers podcast cover"},"contentScale":{"type":"enum","value":"crop"}}
                                  BuilderAssetImage(
                                    assetKey = "jetcaster.cover.google-developers-podcast",
                                    contentDescription = "Google Developers podcast cover",
                                    contentScale = "crop",
                                    modifier =
                                      Modifier.matchParentSize().semantics {
                                        contentDescription = "Google Developers podcast cover"
                                      },
                                  )
                                  // node:podcast-card-google-gradient
                                  // component:shape/linear-gradient
                                  // symbol:Modifier.background(Brush.linearGradient)
                                  // typed-properties:{"direction":{"type":"enum","value":"topToBottom"},"startColor":{"type":"color","value":"#00000000"},"endColor":{"type":"color","value":"#FF000000"}}
                                  Box(
                                    Modifier.matchParentSize()
                                      .background(
                                        Brush.verticalGradient(
                                          listOf(Color(0x00000000), Color(0xFF000000))
                                        )
                                      )
                                  )
                                  // node:podcast-card-google-follow component:m3/icon-button
                                  // symbol:IconButton
                                  // typed-properties:{"alignment":{"type":"enum","value":"topStart"},"selected":{"type":"bool","value":false},"contentDescription":{"type":"string","value":"Follow Google Developers podcast"}}
                                  IconButton(
                                    onClick = {},
                                    modifier =
                                      Modifier.align(Alignment.TopStart)
                                        .semantics {
                                          contentDescription = "Follow Google Developers podcast"
                                        }
                                        .semantics { selected = false },
                                  ) {
                                    // node:podcast-card-google-follow-icon component:m3/icon
                                    // symbol:Icon
                                    // typed-properties:{"iconKey":{"type":"enum","value":"addCircle"},"color":{"type":"colorToken","value":"onSurface"}}
                                    Icon(
                                      imageVector = builderIcon("addCircle"),
                                      contentDescription = null,
                                      tint = MaterialTheme.colorScheme.onSurface,
                                      modifier = Modifier,
                                    )
                                  }
                                  // node:podcast-card-google-title component:m3/text symbol:Text
                                  // typed-properties:{"text":{"type":"string","value":"Google
                                  // Developers
                                  // podcast"},"style":{"type":"typographyToken","value":"bodyMedium"},"color":{"type":"color","value":"#FFFFFFFF"},"maxLines":{"type":"int","value":2},"overflow":{"type":"enum","value":"ellipsis"},"alignment":{"type":"enum","value":"bottomStart"}}
                                  Text(
                                    text = "Google Developers podcast",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color(0xFFFFFFFF),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier =
                                      Modifier.padding(
                                          start = 16.dp,
                                          top = 0.dp,
                                          end = 12.dp,
                                          bottom = 16.dp,
                                        )
                                        .align(Alignment.BottomStart),
                                  )
                                }
                              }
                            }
                          }
                        }
                      }
                    }
                    item(key = "episode-140") {
                      key("episode-140") {
                        // node:main-episode-card component:m3/card symbol:Card
                        // typed-properties:{"stableKey":{"type":"string","value":"episode-140"},"containerColor":{"type":"colorToken","value":"surfaceContainer"},"shape":{"type":"shapeToken","value":"large"}}
                        Card(
                          modifier =
                            Modifier.fillMaxWidth()
                              .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 8.dp),
                          shape = RoundedCornerShape(16.dp),
                          colors = builderCardColors(MaterialTheme.colorScheme.surfaceContainer),
                        ) {
                          Box(Modifier.fillMaxSize()) {
                            // node:main-episode-column component:layout/column symbol:Column
                            // typed-properties:{"verticalSpacingDp":{"type":"float","value":8.0}}
                            Column(
                              modifier =
                                Modifier.padding(
                                  start = 16.dp,
                                  top = 12.dp,
                                  end = 16.dp,
                                  bottom = 12.dp,
                                ),
                              verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                              // node:main-episode-header component:layout/row symbol:Row
                              // typed-properties:{"verticalAlignment":{"type":"enum","value":"top"},"horizontalSpacingDp":{"type":"float","value":16.0}}
                              Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                verticalAlignment = Alignment.Top,
                              ) {
                                // node:main-episode-copy component:layout/column symbol:Column
                                // typed-properties:{"weight":{"type":"float","value":1.0},"verticalSpacingDp":{"type":"float","value":4.0}}
                                Column(
                                  modifier = Modifier.weight(1f),
                                  verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                  // node:main-episode-title component:m3/text symbol:Text
                                  // typed-properties:{"text":{"type":"string","value":"Episode 140:
                                  // Lorem ipsum
                                  // dolor"},"style":{"type":"typographyToken","value":"titleMedium"},"maxLines":{"type":"int","value":2},"overflow":{"type":"enum","value":"ellipsis"}}
                                  Text(
                                    text = "Episode 140: Lorem ipsum dolor",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Color.Unspecified,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier,
                                  )
                                  // node:main-episode-podcast component:m3/text symbol:Text
                                  // typed-properties:{"text":{"type":"string","value":"Android
                                  // Developers
                                  // Backstage"},"style":{"type":"typographyToken","value":"titleSmall"},"color":{"type":"colorToken","value":"onSurfaceVariant"}}
                                  Text(
                                    text = "Android Developers Backstage",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2147483647,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier,
                                  )
                                  // node:main-episode-summary component:m3/text symbol:Text
                                  // typed-properties:{"text":{"type":"string","value":"In this
                                  // episode, Romain, Chet and Tor talked with Mady Melor and Artur
                                  // Tsurkan from the System UI team about...
                                  // Bubbles!"},"style":{"type":"typographyToken","value":"titleSmall"},"maxLines":{"type":"int","value":2},"overflow":{"type":"enum","value":"ellipsis"}}
                                  Text(
                                    text =
                                      "In this episode, Romain, Chet and Tor talked with Mady Melor and Artur Tsurkan from the System UI team about... Bubbles!",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = Color.Unspecified,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier,
                                  )
                                }
                                // node:main-episode-image component:asset/image symbol:Image
                                // typed-properties:{"assetKey":{"type":"assetKey","value":"jetcaster.cover.android-developers-backstage"},"contentDescription":{"type":"string","value":""},"contentScale":{"type":"enum","value":"crop"}}
                                BuilderAssetImage(
                                  assetKey = "jetcaster.cover.android-developers-backstage",
                                  contentDescription = null,
                                  contentScale = "crop",
                                  modifier =
                                    Modifier.size(width = 56.dp, height = 56.dp)
                                      .clip(RoundedCornerShape(12.dp)),
                                )
                              }
                              // node:main-episode-footer component:layout/row symbol:Row
                              // typed-properties:{"verticalAlignment":{"type":"enum","value":"center"}}
                              Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(0.dp),
                                verticalAlignment = Alignment.CenterVertically,
                              ) {
                                // node:main-episode-play component:m3/icon-button symbol:IconButton
                                // typed-properties:{"contentDescription":{"type":"string","value":"Play episode"},"sizeDp":{"type":"float","value":48.0}}
                                IconButton(
                                  onClick = {},
                                  modifier =
                                    Modifier.size(48.dp).semantics {
                                      contentDescription = "Play episode"
                                    },
                                ) {
                                  // node:main-episode-play-icon component:m3/icon symbol:Icon
                                  // typed-properties:{"iconKey":{"type":"enum","value":"playCircle"},"color":{"type":"colorToken","value":"primary"},"sizeDp":{"type":"float","value":36.0}}
                                  Icon(
                                    imageVector = builderIcon("playCircle"),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(36.dp),
                                  )
                                }
                                // node:main-episode-meta component:m3/text symbol:Text
                                // typed-properties:{"text":{"type":"string","value":"May 16, 2024 ·
                                // 42
                                // min"},"style":{"type":"typographyToken","value":"bodySmall"},"weight":{"type":"float","value":1.0},"maxLines":{"type":"int","value":1}}
                                Text(
                                  text = "May 16, 2024 · 42 min",
                                  style = MaterialTheme.typography.bodySmall,
                                  color = Color.Unspecified,
                                  maxLines = 1,
                                  overflow = TextOverflow.Ellipsis,
                                  modifier = Modifier.weight(1f),
                                )
                                // node:main-episode-queue component:m3/icon-button
                                // symbol:IconButton
                                // typed-properties:{"contentDescription":{"type":"string","value":"Add to queue"}}
                                IconButton(
                                  onClick = {},
                                  modifier =
                                    Modifier.semantics { contentDescription = "Add to queue" },
                                ) {
                                  // node:main-episode-queue-icon component:m3/icon symbol:Icon
                                  // typed-properties:{"iconKey":{"type":"enum","value":"playlistAdd"},"color":{"type":"colorToken","value":"onSurfaceVariant"}}
                                  Icon(
                                    imageVector = builderIcon("playlistAdd"),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier,
                                  )
                                }
                                // node:main-episode-more component:m3/icon-button symbol:IconButton
                                // typed-properties:{"contentDescription":{"type":"string","value":"More options"}}
                                IconButton(
                                  onClick = {},
                                  modifier =
                                    Modifier.semantics { contentDescription = "More options" },
                                ) {
                                  // node:main-episode-more-icon component:m3/icon symbol:Icon
                                  // typed-properties:{"iconKey":{"type":"enum","value":"moreVert"},"color":{"type":"colorToken","value":"onSurfaceVariant"}}
                                  Icon(
                                    imageVector = builderIcon("moreVert"),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier,
                                  )
                                }
                              }
                            }
                          }
                        }
                      }
                    }
                  }
                }
                // node:floating-toolbar component:m3/horizontal-floating-toolbar
                // symbol:HorizontalFloatingToolbar
                // typed-properties:{"expanded":{"type":"bool","value":true},"alignment":{"type":"enum","value":"bottomCenter"},"containerColor":{"type":"colorToken","value":"surfaceContainerHighest"}}
                BuilderHorizontalFloatingToolbar(
                  expanded = true,
                  containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                  modifier =
                    Modifier.padding(start = 0.dp, top = 0.dp, end = 0.dp, bottom = 16.dp)
                      .align(Alignment.BottomCenter),
                ) {
                  // node:toolbar-library component:m3/button symbol:Button
                  // typed-properties:{"style":{"type":"enum","value":"filled"},"selected":{"type":"stateEquals","variable":"selectedDestination","value":"Library"}}
                  Button(
                    onClick = { selectedDestination = "Library" },
                    modifier = Modifier.semantics { selected = selectedDestination == "Library" },
                  ) {
                    // node:toolbar-library-row component:layout/row symbol:Row
                    // typed-properties:{"verticalAlignment":{"type":"enum","value":"center"},"horizontalSpacingDp":{"type":"float","value":8.0}}
                    Row(
                      modifier = Modifier,
                      horizontalArrangement = Arrangement.spacedBy(8.dp),
                      verticalAlignment = Alignment.CenterVertically,
                    ) {
                      // node:toolbar-library-icon component:m3/icon symbol:Icon
                      // typed-properties:{"iconKey":{"type":"enum","value":"videoLibrary"}}
                      Icon(
                        imageVector = builderIcon("videoLibrary"),
                        contentDescription = null,
                        tint = Color.Unspecified,
                        modifier = Modifier,
                      )
                      // node:toolbar-library-label component:m3/text symbol:Text
                      // typed-properties:{"text":{"type":"string","value":"Library"},"style":{"type":"typographyToken","value":"labelLarge"}}
                      Text(
                        text = "Library",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.Unspecified,
                        maxLines = 2147483647,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier,
                      )
                    }
                  }
                  // node:toolbar-discover component:m3/button symbol:Button
                  // typed-properties:{"style":{"type":"enum","value":"fab"},"selected":{"type":"stateEquals","variable":"selectedDestination","value":"Discover"},"containerColor":{"type":"colorToken","value":"tertiary"}}
                  FloatingActionButton(
                    onClick = { selectedDestination = "Discover" },
                    containerColor = MaterialTheme.colorScheme.tertiary,
                    contentColor = Color.Unspecified,
                    modifier = Modifier.semantics { selected = selectedDestination == "Discover" },
                  ) {
                    // node:toolbar-discover-row component:layout/row symbol:Row
                    // typed-properties:{"verticalAlignment":{"type":"enum","value":"center"},"horizontalSpacingDp":{"type":"float","value":8.0}}
                    Row(
                      modifier = Modifier,
                      horizontalArrangement = Arrangement.spacedBy(8.dp),
                      verticalAlignment = Alignment.CenterVertically,
                    ) {
                      // node:toolbar-discover-icon component:m3/icon symbol:Icon
                      // typed-properties:{"iconKey":{"type":"enum","value":"genres"},"color":{"type":"colorToken","value":"onTertiary"}}
                      Icon(
                        imageVector = builderIcon("genres"),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onTertiary,
                        modifier = Modifier,
                      )
                      // node:toolbar-discover-label component:m3/text symbol:Text
                      // typed-properties:{"text":{"type":"string","value":"Discover"},"style":{"type":"typographyToken","value":"labelLarge"},"color":{"type":"colorToken","value":"onTertiary"}}
                      Text(
                        text = "Discover",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onTertiary,
                        maxLines = 2147483647,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier,
                      )
                    }
                  }
                }
              }
              if (false) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
          }
        }
      },
      supportingPane = {
        key("podcast-detail-scroll") {
          // node:detail-scaffold component:layout/scaffold symbol:Scaffold
          // typed-properties:{"containerColor":{"type":"colorToken","value":"surface"},"scrollStateKey":{"type":"string","value":"podcast-detail-scroll"}}
          Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.surface,
            topBar = {
              // node:detail-top-bar component:layout/row symbol:Row
              // typed-properties:{"verticalAlignment":{"type":"enum","value":"center"}}
              Row(
                modifier =
                  Modifier.fillMaxWidth()
                    .padding(start = 24.dp, top = 16.dp, end = 16.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(0.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                // node:detail-top-title component:m3/text symbol:Text
                // typed-properties:{"text":{"type":"string","value":"Podcast
                // details"},"style":{"type":"typographyToken","value":"titleLarge"},"weight":{"type":"float","value":1.0}}
                Text(
                  text = "Podcast details",
                  style = MaterialTheme.typography.titleLarge,
                  color = Color.Unspecified,
                  maxLines = 2147483647,
                  overflow = TextOverflow.Ellipsis,
                  modifier = Modifier.weight(1f),
                )
                // node:detail-more component:m3/icon-button symbol:IconButton
                // typed-properties:{"contentDescription":{"type":"string","value":"Podcast
                // options"}}
                IconButton(
                  onClick = {},
                  modifier = Modifier.semantics { contentDescription = "Podcast options" },
                ) {
                  // node:detail-more-icon component:m3/icon symbol:Icon
                  // typed-properties:{"iconKey":{"type":"enum","value":"moreVert"}}
                  Icon(
                    imageVector = builderIcon("moreVert"),
                    contentDescription = null,
                    tint = Color.Unspecified,
                    modifier = Modifier,
                  )
                }
              }
            },
            snackbarHost = {},
          ) { contentPadding ->
            Box(Modifier.padding(contentPadding)) {
              key("podcast-detail-scroll") {
                // node:detail-list component:layout/lazy-column symbol:LazyColumn
                // typed-properties:{"scrollStateKey":{"type":"string","value":"podcast-detail-scroll"},"contentPadding":{"type":"padding","startDp":24,"topDp":8,"endDp":24,"bottomDp":24},"verticalSpacingDp":{"type":"float","value":16.0}}
                LazyColumn(
                  contentPadding =
                    PaddingValues(start = 24.dp, top = 8.dp, end = 24.dp, bottom = 24.dp),
                  verticalArrangement = Arrangement.spacedBy(16.dp),
                  modifier = Modifier.fillMaxSize(),
                ) {
                  item(key = "android-developers-backstage-header") {
                    key("android-developers-backstage-header") {
                      // node:detail-hero component:m3/card symbol:Card
                      // typed-properties:{"stableKey":{"type":"string","value":"android-developers-backstage-header"},"containerColor":{"type":"colorToken","value":"surfaceContainerLow"},"shape":{"type":"shapeToken","value":"large"}}
                      Card(
                        modifier = Modifier,
                        shape = RoundedCornerShape(16.dp),
                        colors = builderCardColors(MaterialTheme.colorScheme.surfaceContainerLow),
                      ) {
                        Box(Modifier.fillMaxSize()) {
                          // node:detail-hero-row component:layout/row symbol:Row
                          // typed-properties:{"verticalAlignment":{"type":"enum","value":"center"},"horizontalSpacingDp":{"type":"float","value":20.0}}
                          Row(
                            modifier =
                              Modifier.padding(
                                start = 20.dp,
                                top = 20.dp,
                                end = 20.dp,
                                bottom = 20.dp,
                              ),
                            horizontalArrangement = Arrangement.spacedBy(20.dp),
                            verticalAlignment = Alignment.CenterVertically,
                          ) {
                            // node:detail-artwork component:asset/image symbol:Image
                            // typed-properties:{"assetKey":{"type":"assetKey","value":"jetcaster.cover.android-developers-backstage"},"contentDescription":{"type":"string","value":"Android Developers Backstage cover"},"contentScale":{"type":"enum","value":"crop"}}
                            BuilderAssetImage(
                              assetKey = "jetcaster.cover.android-developers-backstage",
                              contentDescription = "Android Developers Backstage cover",
                              contentScale = "crop",
                              modifier =
                                Modifier.size(width = 152.dp, height = 152.dp)
                                  .clip(RoundedCornerShape(16.dp))
                                  .semantics {
                                    contentDescription = "Android Developers Backstage cover"
                                  },
                            )
                            // node:detail-hero-copy component:layout/column symbol:Column
                            // typed-properties:{"weight":{"type":"float","value":1.0},"verticalSpacingDp":{"type":"float","value":8.0}}
                            Column(
                              modifier = Modifier.weight(1f),
                              verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                              // node:detail-podcast-title component:m3/text symbol:Text
                              // typed-properties:{"text":{"type":"string","value":"Android
                              // Developers
                              // Backstage"},"style":{"type":"typographyToken","value":"headlineSmall"},"maxLines":{"type":"int","value":3}}
                              Text(
                                text = "Android Developers Backstage",
                                style = MaterialTheme.typography.headlineSmall,
                                color = Color.Unspecified,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier,
                              )
                              // node:detail-author component:m3/text symbol:Text
                              // typed-properties:{"text":{"type":"string","value":"Android
                              // Developers"},"style":{"type":"typographyToken","value":"bodyLarge"},"color":{"type":"colorToken","value":"onSurfaceVariant"}}
                              Text(
                                text = "Android Developers",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2147483647,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier,
                              )
                              // node:detail-follow component:m3/button symbol:Button
                              // typed-properties:{"style":{"type":"enum","value":"filledTonal"},"selected":{"type":"bool","value":true}}
                              FilledTonalButton(
                                onClick = { Unit },
                                modifier = Modifier.semantics { selected = true },
                              ) {
                                // node:detail-follow-label component:m3/text symbol:Text
                                // typed-properties:{"text":{"type":"string","value":"Following"},"style":{"type":"typographyToken","value":"labelLarge"}}
                                Text(
                                  text = "Following",
                                  style = MaterialTheme.typography.labelLarge,
                                  color = Color.Unspecified,
                                  maxLines = 2147483647,
                                  overflow = TextOverflow.Ellipsis,
                                  modifier = Modifier,
                                )
                              }
                            }
                          }
                        }
                      }
                    }
                  }
                  item(key = "detail-description") {
                    // node:detail-description component:m3/text symbol:Text
                    // typed-properties:{"text":{"type":"string","value":"An official podcast from
                    // the Android team, featuring conversations about the latest platform, tools,
                    // libraries, and people behind Android
                    // development."},"style":{"type":"typographyToken","value":"bodyLarge"},"color":{"type":"colorToken","value":"onSurfaceVariant"}}
                    Text(
                      text =
                        "An official podcast from the Android team, featuring conversations about the latest platform, tools, libraries, and people behind Android development.",
                      style = MaterialTheme.typography.bodyLarge,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                      maxLines = 2147483647,
                      overflow = TextOverflow.Ellipsis,
                      modifier = Modifier,
                    )
                  }
                  item(key = "detail-metadata") {
                    // node:detail-metadata component:layout/row symbol:Row
                    // typed-properties:{"horizontalSpacingDp":{"type":"float","value":8.0}}
                    Row(
                      modifier = Modifier,
                      horizontalArrangement = Arrangement.spacedBy(8.dp),
                      verticalAlignment = Alignment.CenterVertically,
                    ) {
                      // node:detail-category-chip component:m3/filter-chip symbol:FilterChip
                      // typed-properties:{"selected":{"type":"bool","value":false},"enabled":{"type":"bool","value":false}}
                      FilterChip(
                        selected = false,
                        onClick = { Unit },
                        enabled = false,
                        shape = RoundedCornerShape(16.dp),
                        label = {
                          // node:detail-category-label component:m3/text symbol:Text
                          // typed-properties:{"text":{"type":"string","value":"Technology"},"style":{"type":"typographyToken","value":"labelLarge"}}
                          Text(
                            text = "Technology",
                            style = MaterialTheme.typography.labelLarge,
                            color = Color.Unspecified,
                            maxLines = 2147483647,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier,
                          )
                        },
                      )
                    }
                  }
                  item(key = "detail-divider") {
                    // node:detail-divider component:m3/horizontal-divider symbol:HorizontalDivider
                    // typed-properties:{"color":{"type":"colorToken","value":"outlineVariant"}}
                    HorizontalDivider(
                      color = MaterialTheme.colorScheme.outlineVariant,
                      modifier = Modifier,
                    )
                  }
                  item(key = "detail-episodes-title") {
                    // node:detail-episodes-title component:m3/text symbol:Text
                    // typed-properties:{"text":{"type":"string","value":"Latest
                    // episodes"},"style":{"type":"typographyToken","value":"titleLarge"}}
                    Text(
                      text = "Latest episodes",
                      style = MaterialTheme.typography.titleLarge,
                      color = Color.Unspecified,
                      maxLines = 2147483647,
                      overflow = TextOverflow.Ellipsis,
                      modifier = Modifier,
                    )
                  }
                  item(key = "episode-140-detail") {
                    key("episode-140-detail") {
                      // node:detail-episode-140 component:m3/card symbol:Card
                      // typed-properties:{"stableKey":{"type":"string","value":"episode-140-detail"},"containerColor":{"type":"colorToken","value":"surfaceContainer"},"shape":{"type":"shapeToken","value":"large"}}
                      Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = builderCardColors(MaterialTheme.colorScheme.surfaceContainer),
                      ) {
                        Box(Modifier.fillMaxSize()) {
                          // node:detail-episode-140-column component:layout/column symbol:Column
                          // typed-properties:{"verticalSpacingDp":{"type":"float","value":8.0}}
                          Column(
                            modifier =
                              Modifier.padding(
                                start = 16.dp,
                                top = 16.dp,
                                end = 16.dp,
                                bottom = 12.dp,
                              ),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                          ) {
                            // node:detail-episode-140-title component:m3/text symbol:Text
                            // typed-properties:{"text":{"type":"string","value":"Episode 140: Lorem
                            // ipsum
                            // dolor"},"style":{"type":"typographyToken","value":"titleMedium"},"maxLines":{"type":"int","value":2}}
                            Text(
                              text = "Episode 140: Lorem ipsum dolor",
                              style = MaterialTheme.typography.titleMedium,
                              color = Color.Unspecified,
                              maxLines = 2,
                              overflow = TextOverflow.Ellipsis,
                              modifier = Modifier,
                            )
                            // node:detail-episode-140-summary component:m3/text symbol:Text
                            // typed-properties:{"text":{"type":"string","value":"Romain, Chet and
                            // Tor talk with the System UI team about
                            // Bubbles."},"style":{"type":"typographyToken","value":"bodyMedium"},"color":{"type":"colorToken","value":"onSurfaceVariant"},"maxLines":{"type":"int","value":2},"overflow":{"type":"enum","value":"ellipsis"}}
                            Text(
                              text =
                                "Romain, Chet and Tor talk with the System UI team about Bubbles.",
                              style = MaterialTheme.typography.bodyMedium,
                              color = MaterialTheme.colorScheme.onSurfaceVariant,
                              maxLines = 2,
                              overflow = TextOverflow.Ellipsis,
                              modifier = Modifier,
                            )
                            // node:detail-episode-140-footer component:layout/row symbol:Row
                            // typed-properties:{"verticalAlignment":{"type":"enum","value":"center"}}
                            Row(
                              modifier = Modifier.fillMaxWidth(),
                              horizontalArrangement = Arrangement.spacedBy(0.dp),
                              verticalAlignment = Alignment.CenterVertically,
                            ) {
                              // node:detail-episode-140-play component:m3/icon-button
                              // symbol:IconButton
                              // typed-properties:{"contentDescription":{"type":"string","value":"Play Episode 140"}}
                              IconButton(
                                onClick = {},
                                modifier =
                                  Modifier.semantics { contentDescription = "Play Episode 140" },
                              ) {
                                // node:detail-episode-140-play-icon component:m3/icon symbol:Icon
                                // typed-properties:{"iconKey":{"type":"enum","value":"playCircle"},"color":{"type":"colorToken","value":"primary"}}
                                Icon(
                                  imageVector = builderIcon("playCircle"),
                                  contentDescription = null,
                                  tint = MaterialTheme.colorScheme.primary,
                                  modifier = Modifier,
                                )
                              }
                              // node:detail-episode-140-meta component:m3/text symbol:Text
                              // typed-properties:{"text":{"type":"string","value":"May 16, 2024 ·
                              // 42
                              // min"},"style":{"type":"typographyToken","value":"bodySmall"},"weight":{"type":"float","value":1.0}}
                              Text(
                                text = "May 16, 2024 · 42 min",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Unspecified,
                                maxLines = 2147483647,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                              )
                              // node:detail-episode-140-queue component:m3/icon-button
                              // symbol:IconButton
                              // typed-properties:{"contentDescription":{"type":"string","value":"Add Episode 140 to queue"}}
                              IconButton(
                                onClick = {},
                                modifier =
                                  Modifier.semantics {
                                    contentDescription = "Add Episode 140 to queue"
                                  },
                              ) {
                                // node:detail-episode-140-queue-icon component:m3/icon symbol:Icon
                                // typed-properties:{"iconKey":{"type":"enum","value":"playlistAdd"}}
                                Icon(
                                  imageVector = builderIcon("playlistAdd"),
                                  contentDescription = null,
                                  tint = Color.Unspecified,
                                  modifier = Modifier,
                                )
                              }
                            }
                          }
                        }
                      }
                    }
                  }
                  item(key = "episode-139-detail") {
                    key("episode-139-detail") {
                      // node:detail-episode-139 component:m3/card symbol:Card
                      // typed-properties:{"stableKey":{"type":"string","value":"episode-139-detail"},"containerColor":{"type":"colorToken","value":"surfaceContainer"},"shape":{"type":"shapeToken","value":"large"}}
                      Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = builderCardColors(MaterialTheme.colorScheme.surfaceContainer),
                      ) {
                        Box(Modifier.fillMaxSize()) {
                          // node:detail-episode-139-column component:layout/column symbol:Column
                          // typed-properties:{"verticalSpacingDp":{"type":"float","value":8.0}}
                          Column(
                            modifier =
                              Modifier.padding(
                                start = 16.dp,
                                top = 16.dp,
                                end = 16.dp,
                                bottom = 12.dp,
                              ),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                          ) {
                            // node:detail-episode-139-title component:m3/text symbol:Text
                            // typed-properties:{"text":{"type":"string","value":"Episode 139:
                            // Compose across
                            // screens"},"style":{"type":"typographyToken","value":"titleMedium"},"maxLines":{"type":"int","value":2}}
                            Text(
                              text = "Episode 139: Compose across screens",
                              style = MaterialTheme.typography.titleMedium,
                              color = Color.Unspecified,
                              maxLines = 2,
                              overflow = TextOverflow.Ellipsis,
                              modifier = Modifier,
                            )
                            // node:detail-episode-139-summary component:m3/text symbol:Text
                            // typed-properties:{"text":{"type":"string","value":"A conversation
                            // about adaptive layouts, large screens, and practical Compose
                            // architecture."},"style":{"type":"typographyToken","value":"bodyMedium"},"color":{"type":"colorToken","value":"onSurfaceVariant"},"maxLines":{"type":"int","value":2},"overflow":{"type":"enum","value":"ellipsis"}}
                            Text(
                              text =
                                "A conversation about adaptive layouts, large screens, and practical Compose architecture.",
                              style = MaterialTheme.typography.bodyMedium,
                              color = MaterialTheme.colorScheme.onSurfaceVariant,
                              maxLines = 2,
                              overflow = TextOverflow.Ellipsis,
                              modifier = Modifier,
                            )
                            // node:detail-episode-139-footer component:layout/row symbol:Row
                            // typed-properties:{"verticalAlignment":{"type":"enum","value":"center"}}
                            Row(
                              modifier = Modifier.fillMaxWidth(),
                              horizontalArrangement = Arrangement.spacedBy(0.dp),
                              verticalAlignment = Alignment.CenterVertically,
                            ) {
                              // node:detail-episode-139-play component:m3/icon-button
                              // symbol:IconButton
                              // typed-properties:{"contentDescription":{"type":"string","value":"Play Episode 139"}}
                              IconButton(
                                onClick = {},
                                modifier =
                                  Modifier.semantics { contentDescription = "Play Episode 139" },
                              ) {
                                // node:detail-episode-139-play-icon component:m3/icon symbol:Icon
                                // typed-properties:{"iconKey":{"type":"enum","value":"playCircle"},"color":{"type":"colorToken","value":"primary"}}
                                Icon(
                                  imageVector = builderIcon("playCircle"),
                                  contentDescription = null,
                                  tint = MaterialTheme.colorScheme.primary,
                                  modifier = Modifier,
                                )
                              }
                              // node:detail-episode-139-meta component:m3/text symbol:Text
                              // typed-properties:{"text":{"type":"string","value":"May 9, 2024 · 38
                              // min"},"style":{"type":"typographyToken","value":"bodySmall"},"weight":{"type":"float","value":1.0}}
                              Text(
                                text = "May 9, 2024 · 38 min",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Unspecified,
                                maxLines = 2147483647,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                              )
                              // node:detail-episode-139-queue component:m3/icon-button
                              // symbol:IconButton
                              // typed-properties:{"contentDescription":{"type":"string","value":"Add Episode 139 to queue"}}
                              IconButton(
                                onClick = {},
                                modifier =
                                  Modifier.semantics {
                                    contentDescription = "Add Episode 139 to queue"
                                  },
                              ) {
                                // node:detail-episode-139-queue-icon component:m3/icon symbol:Icon
                                // typed-properties:{"iconKey":{"type":"enum","value":"playlistAdd"}}
                                Icon(
                                  imageVector = builderIcon("playlistAdd"),
                                  contentDescription = null,
                                  tint = Color.Unspecified,
                                  modifier = Modifier,
                                )
                              }
                            }
                          }
                        }
                      }
                    }
                  }
                }
              }
            }
          }
        }
      },
    )
  }
}

// Compatibility helpers are explicit export diagnostics, not claims of API parity.
@Composable
private fun builderCardColors(containerColor: Color) =
  CardDefaults.cardColors(containerColor = containerColor)

@Composable
private fun BuilderSupportingPaneScaffold(
  modifier: Modifier,
  mainPaneWidth: Dp,
  supportingPaneWidth: Dp,
  paneSpacing: Dp,
  layoutMode: String,
  mainPaneVisible: Boolean,
  supportingPaneVisible: Boolean,
  mainPane: @Composable () -> Unit,
  supportingPane: @Composable () -> Unit,
) {
  BoxWithConstraints(modifier) {
    val expanded = layoutMode == "expandedTwoPane" && maxWidth >= 1280.dp
    if (expanded) {
      Row(Modifier.fillMaxSize()) {
        if (mainPaneVisible) Box(Modifier.width(mainPaneWidth).fillMaxHeight()) { mainPane() }
        if (mainPaneVisible && supportingPaneVisible) Spacer(Modifier.width(paneSpacing))
        if (supportingPaneVisible)
          Box(Modifier.width(supportingPaneWidth).fillMaxHeight()) { supportingPane() }
      }
    } else if (mainPaneVisible) {
      mainPane()
    } else if (supportingPaneVisible) {
      supportingPane()
    }
  }
}

@Composable
private fun BuilderHorizontalCarousel(
  kind: String,
  itemWidth: Dp,
  spacing: Dp,
  contentPaddingStart: Dp,
  content: @Composable RowScope.(Dp) -> Unit,
) {
  check(kind == "uncontained") { "Unsupported carousel kind: $kind" }
  Row(
    Modifier.padding(start = contentPaddingStart),
    horizontalArrangement = Arrangement.spacedBy(spacing),
  ) {
    content(itemWidth)
  }
}

@Composable
private fun BuilderSearchBar(
  expanded: Boolean,
  tonalElevation: Dp,
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit,
) {
  Surface(
    modifier.height(56.dp).semantics {
      stateDescription = if (expanded) "expanded" else "collapsed"
    },
    shape = CircleShape,
    color = MaterialTheme.colorScheme.surfaceContainerHigh,
    tonalElevation = tonalElevation,
  ) {
    Box(Modifier.fillMaxSize()) { content() }
  }
}

@Composable
private fun BuilderSearchInputField(
  value: String,
  onValueChange: (String) -> Unit,
  enabled: Boolean,
  leadingIcon: @Composable () -> Unit,
  placeholder: @Composable () -> Unit,
  trailingIcon: @Composable () -> Unit,
) {
  Row(
    Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
    horizontalArrangement = Arrangement.spacedBy(16.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    leadingIcon()
    BasicTextField(
      value,
      onValueChange,
      Modifier.weight(1f),
      enabled = enabled,
      decorationBox = { inner ->
        if (value.isEmpty()) placeholder()
        inner()
      },
    )
    trailingIcon()
  }
}

@Composable
private fun BuilderSnackbarHost(visible: Boolean) {
  if (visible) Snackbar { Text("Snackbar") }
}

@Composable
private fun BuilderHorizontalFloatingToolbar(
  expanded: Boolean,
  containerColor: Color,
  modifier: Modifier = Modifier,
  content: @Composable RowScope.() -> Unit,
) {
  Surface(
    modifier.semantics { stateDescription = if (expanded) "expanded" else "collapsed" },
    shape = CircleShape,
    color = containerColor,
    shadowElevation = 8.dp,
  ) {
    Row(
      Modifier.padding(6.dp),
      horizontalArrangement = Arrangement.spacedBy(6.dp),
      verticalAlignment = Alignment.CenterVertically,
      content = content,
    )
  }
}

@Composable
private fun BuilderRadialGradient(
  modifier: Modifier,
  innerColor: Color,
  innerAlpha: Float,
  outerColor: Color,
  centerFraction: Offset,
) {
  Box(
    modifier.drawBehind {
      drawRect(
        Brush.radialGradient(
          listOf(innerColor.copy(alpha = innerAlpha), outerColor),
          center = Offset(size.width * centerFraction.x, size.height * centerFraction.y),
          radius = size.maxDimension * .82f,
        )
      )
    }
  )
}

// Asset adapter: compose-preview-project-owned-jetcaster-artwork/v1.
@Composable
private fun BuilderAssetImage(
  assetKey: String,
  contentDescription: String?,
  contentScale: String,
  modifier: Modifier = Modifier,
) {
  check(contentScale == "crop" || contentScale.isEmpty()) {
    "Unsupported content scale: $contentScale"
  }
  ProjectOwnedJetcasterArtwork(
    assetKey = assetKey,
    contentDescription = contentDescription,
    modifier = modifier,
  )
}

private fun builderIcon(key: String): ImageVector =
  when (key) {
    "search" -> Icons.Default.Search
    "accountCircle" -> Icons.Default.AccountCircle
    "check" -> Icons.Default.Check
    "checkCircle" -> Icons.Default.CheckCircle
    "addCircle" -> Icons.Default.AddCircle
    "playCircle" -> Icons.Default.PlayCircle
    "playlistAdd" -> Icons.Default.PlaylistAdd
    "moreVert" -> Icons.Default.MoreVert
    "videoLibrary" -> Icons.Default.VideoLibrary
    else -> Icons.Default.Category
  }
