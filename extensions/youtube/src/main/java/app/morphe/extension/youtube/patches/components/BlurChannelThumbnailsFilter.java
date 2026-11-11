package app.morphe.extension.youtube.patches.components;

import app.morphe.extension.shared.patches.components.BufferAsciiStrings;
import app.morphe.extension.shared.patches.components.ContextInterface;
import app.morphe.extension.shared.patches.components.Filter;
import app.morphe.extension.shared.patches.components.StringFilterGroup;
import app.morphe.extension.youtube.patches.BlurChannelThumbnailsPatch;
import app.morphe.extension.youtube.settings.Settings;

/**
 * Never hides anything. Reads the buffer of each video card to learn
 * which video IDs belong to the channels that should be blurred.
 */
@SuppressWarnings("unused")
public final class BlurChannelThumbnailsFilter extends Filter {

    /** Always the start of the path. Same containers as the keyword filter. */
    private final StringFilterGroup startsWithFilter = new StringFilterGroup(
            Settings.BLUR_CHANNEL_THUMBNAILS,
            "home_video_with_context.e",
            "search_video_with_context.e",
            "video_with_context.e",
            "related_video_with_context.e",
            "video_lockup_with_attachment.e",
            "compact_video.e",
            "inline_shorts",
            "shorts_video_cell",
            "shorts_pivot_item.e"
    );

    /** Never the start of the path. */
    private final StringFilterGroup containsFilter = new StringFilterGroup(
            Settings.BLUR_CHANNEL_THUMBNAILS,
            "shorts_lockup_cell.e",
            "video_card.e"
    );

    public BlurChannelThumbnailsFilter() {
        addPathCallbacks(startsWithFilter, containsFilter);
    }

    @Override
    public boolean isFiltered(ContextInterface contextInterface, String identifier, String accessibility,
                              CharSequence path, byte[] buffer, BufferAsciiStrings asciiStrings,
                              StringFilterGroup matchedGroup, FilterContentType contentType, int contentIndex) {
        if (contentIndex != 0 && matchedGroup == startsWithFilter) {
            return false;
        }

        BlurChannelThumbnailsPatch.processCardBuffer(buffer);
        return false;
    }
}
