package app.morphe.patches.youtube.layout.thumbnails

import app.morphe.patcher.patch.patch
import app.morphe.patches.shared.misc.litho.filter.addLithoFilter
import app.morphe.patches.shared.misc.settings.preference.InputType
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference.Sorting
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.settings.preference.TextPreference
import app.morphe.patches.youtube.layout.flyout.flyoutPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.imageurlhook.addImageURLErrorCallbackHook
import app.morphe.patches.youtube.misc.imageurlhook.addImageURLHook
import app.morphe.patches.youtube.misc.imageurlhook.addImageURLSuccessCallbackHook
import app.morphe.patches.youtube.misc.imageurlhook.cronetImageURLHookPatch
import app.morphe.patches.youtube.misc.litho.filter.lithoFilterPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.util.setExtensionIsPatchIncluded

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/BlurChannelThumbnailsPatch;"
private const val FILTER_CLASS =
    "Lapp/morphe/extension/youtube/patches/components/BlurChannelThumbnailsFilter;"

@Suppress("unused")
val blurChannelThumbnailsPatch = patch(
    name = "Blur channel thumbnails",
    description = "Adds options to blur the thumbnails of chosen channels, with a custom text over them, " +
        "and to show them again from the video menu.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        lithoFilterPatch,
        flyoutPatch,
        cronetImageURLHookPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.FEED.addPreferences(
            PreferenceScreenPreference(
                key = "morphe_blur_channel_thumbnails_screen",
                sorting = Sorting.UNSORTED,
                preferences = setOf(
                    SwitchPreference("morphe_blur_channel_thumbnails"),
                    TextPreference(
                        key = "morphe_blur_channel_thumbnails_channels",
                        inputType = InputType.TEXT_MULTI_LINE,
                    ),
                    TextPreference(
                        key = "morphe_blur_channel_thumbnails_text",
                        inputType = InputType.TEXT,
                    ),
                    SwitchPreference("morphe_blur_channel_thumbnails_flyout_menu"),
                    SwitchPreference("morphe_blur_channel_thumbnails_low_res"),
                ),
            ),
        )

        addLithoFilter(FILTER_CLASS)

        addImageURLHook(EXTENSION_CLASS)
        addImageURLSuccessCallbackHook(EXTENSION_CLASS)
        addImageURLErrorCallbackHook(EXTENSION_CLASS)

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}
