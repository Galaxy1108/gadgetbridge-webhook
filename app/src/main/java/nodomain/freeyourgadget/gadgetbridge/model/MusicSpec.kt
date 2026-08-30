/*  Copyright (C) 2016-2024 Andreas Shimokawa, Carsten Pfeiffer, Daniele
    Gobbetti, Taavi Eomäe

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.model

data class MusicSpec @JvmOverloads constructor(
    var artist: String? = null,
    var album: String? = null,
    var track: String? = null,
    var duration: Int = MUSIC_UNKNOWN,
    var trackCount: Int = MUSIC_UNKNOWN,
    var trackNr: Int = MUSIC_UNKNOWN
) {
    fun copyOf(): MusicSpec = copy() // data class .copy() method is not available from Java code

    companion object {
        const val MUSIC_UNKNOWN: Int = -1
        const val MUSIC_UNDEFINED: Int = 0
        const val MUSIC_PLAY: Int = 1
        const val MUSIC_PAUSE: Int = 2
        const val MUSIC_PLAYPAUSE: Int = 3
        const val MUSIC_NEXT: Int = 4
        const val MUSIC_PREVIOUS: Int = 5
    }
}