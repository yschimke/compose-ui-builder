package ee.schimke.composeai.uibuilder

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.ui.graphics.vector.ImageVector

/** The Google Material icons that the builder can render and export without external assets. */
internal data class GoogleMaterialIcon(
  val key: String,
  val label: String,
  val imageVector: ImageVector,
  val composeExpression: String,
)

internal val GoogleMaterialIcons: List<GoogleMaterialIcon> =
  listOf(
      GoogleMaterialIcon(
        "accessTime",
        "Access time",
        Icons.Filled.AccessTime,
        "Icons.Filled.AccessTime",
      ),
      GoogleMaterialIcon(
        "accountCircle",
        "Account circle",
        Icons.Filled.AccountCircle,
        "Icons.Filled.AccountCircle",
      ),
      GoogleMaterialIcon("add", "Add", Icons.Filled.Add, "Icons.Filled.Add"),
      GoogleMaterialIcon(
        "addCircle",
        "Add circle",
        Icons.Filled.AddCircle,
        "Icons.Filled.AddCircle",
      ),
      GoogleMaterialIcon(
        "arrowBack",
        "Arrow back",
        Icons.AutoMirrored.Filled.ArrowBack,
        "Icons.AutoMirrored.Filled.ArrowBack",
      ),
      GoogleMaterialIcon(
        "arrowForward",
        "Arrow forward",
        Icons.AutoMirrored.Filled.ArrowForward,
        "Icons.AutoMirrored.Filled.ArrowForward",
      ),
      GoogleMaterialIcon("bookmark", "Bookmark", Icons.Filled.Bookmark, "Icons.Filled.Bookmark"),
      GoogleMaterialIcon(
        "bookmarkBorder",
        "Bookmark border",
        Icons.Outlined.BookmarkBorder,
        "Icons.Outlined.BookmarkBorder",
      ),
      GoogleMaterialIcon(
        "calendarMonth",
        "Calendar month",
        Icons.Filled.CalendarMonth,
        "Icons.Filled.CalendarMonth",
      ),
      GoogleMaterialIcon("cameraAlt", "Camera", Icons.Filled.CameraAlt, "Icons.Filled.CameraAlt"),
      GoogleMaterialIcon("check", "Check", Icons.Filled.Check, "Icons.Filled.Check"),
      GoogleMaterialIcon(
        "checkCircle",
        "Check circle",
        Icons.Filled.CheckCircle,
        "Icons.Filled.CheckCircle",
      ),
      GoogleMaterialIcon(
        "chevronRight",
        "Chevron right",
        Icons.Filled.ChevronRight,
        "Icons.Filled.ChevronRight",
      ),
      GoogleMaterialIcon("close", "Close", Icons.Filled.Close, "Icons.Filled.Close"),
      GoogleMaterialIcon("coffee", "Coffee", Icons.Filled.Coffee, "Icons.Filled.Coffee"),
      GoogleMaterialIcon("delete", "Delete", Icons.Filled.Delete, "Icons.Filled.Delete"),
      GoogleMaterialIcon("download", "Download", Icons.Filled.Download, "Icons.Filled.Download"),
      GoogleMaterialIcon("edit", "Edit", Icons.Filled.Edit, "Icons.Filled.Edit"),
      GoogleMaterialIcon("email", "Email", Icons.Filled.Email, "Icons.Filled.Email"),
      GoogleMaterialIcon(
        "expandMore",
        "Expand more",
        Icons.Filled.ExpandMore,
        "Icons.Filled.ExpandMore",
      ),
      GoogleMaterialIcon("favorite", "Favorite", Icons.Filled.Favorite, "Icons.Filled.Favorite"),
      GoogleMaterialIcon("genres", "Genres", Icons.Filled.Category, "Icons.Filled.Category"),
      GoogleMaterialIcon("home", "Home", Icons.Filled.Home, "Icons.Filled.Home"),
      GoogleMaterialIcon("image", "Image", Icons.Filled.Image, "Icons.Filled.Image"),
      GoogleMaterialIcon("info", "Info", Icons.Filled.Info, "Icons.Filled.Info"),
      GoogleMaterialIcon(
        "locationOn",
        "Location",
        Icons.Filled.LocationOn,
        "Icons.Filled.LocationOn",
      ),
      GoogleMaterialIcon("lock", "Lock", Icons.Filled.Lock, "Icons.Filled.Lock"),
      GoogleMaterialIcon("menu", "Menu", Icons.Filled.Menu, "Icons.Filled.Menu"),
      GoogleMaterialIcon(
        "moreVert",
        "More vertically",
        Icons.Filled.MoreVert,
        "Icons.Filled.MoreVert",
      ),
      GoogleMaterialIcon(
        "notifications",
        "Notifications",
        Icons.Filled.Notifications,
        "Icons.Filled.Notifications",
      ),
      GoogleMaterialIcon(
        "pauseCircle",
        "Pause circle",
        Icons.Filled.PauseCircle,
        "Icons.Filled.PauseCircle",
      ),
      GoogleMaterialIcon("person", "Person", Icons.Filled.Person, "Icons.Filled.Person"),
      GoogleMaterialIcon("phone", "Phone", Icons.Filled.Phone, "Icons.Filled.Phone"),
      GoogleMaterialIcon(
        "playCircle",
        "Play circle",
        Icons.Filled.PlayCircle,
        "Icons.Filled.PlayCircle",
      ),
      GoogleMaterialIcon(
        "playlistAdd",
        "Playlist add",
        Icons.AutoMirrored.Filled.PlaylistAdd,
        "Icons.AutoMirrored.Filled.PlaylistAdd",
      ),
      GoogleMaterialIcon("refresh", "Refresh", Icons.Filled.Refresh, "Icons.Filled.Refresh"),
      GoogleMaterialIcon("remove", "Remove", Icons.Filled.Remove, "Icons.Filled.Remove"),
      GoogleMaterialIcon("search", "Search", Icons.Filled.Search, "Icons.Filled.Search"),
      GoogleMaterialIcon("settings", "Settings", Icons.Filled.Settings, "Icons.Filled.Settings"),
      GoogleMaterialIcon("share", "Share", Icons.Filled.Share, "Icons.Filled.Share"),
      GoogleMaterialIcon("star", "Star", Icons.Filled.Star, "Icons.Filled.Star"),
      GoogleMaterialIcon(
        "stopCircle",
        "Stop circle",
        Icons.Filled.StopCircle,
        "Icons.Filled.StopCircle",
      ),
      GoogleMaterialIcon("upload", "Upload", Icons.Filled.Upload, "Icons.Filled.Upload"),
      GoogleMaterialIcon(
        "videoLibrary",
        "Video library",
        Icons.Filled.VideoLibrary,
        "Icons.Filled.VideoLibrary",
      ),
      GoogleMaterialIcon(
        "visibility",
        "Visibility",
        Icons.Filled.Visibility,
        "Icons.Filled.Visibility",
      ),
      GoogleMaterialIcon("warning", "Warning", Icons.Filled.Warning, "Icons.Filled.Warning"),
    )
    .sortedBy(GoogleMaterialIcon::label)

internal fun googleMaterialIcon(key: String): GoogleMaterialIcon? =
  GoogleMaterialIcons.firstOrNull {
    it.key == key
  }
