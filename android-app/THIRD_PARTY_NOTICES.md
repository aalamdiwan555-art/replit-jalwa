# Third-party notices

## Smart AutoClicker / Klick’r

- Repository: https://github.com/Nain57/Smart-AutoClicker
- Original author/maintainer: Nain57 and contributors
- License: GNU General Public License v3.0
- Reused files/components: none in this build
- Modifications: none, because no upstream source file was copied

The repository was consulted as an openly licensed reference for the requested testing-utility domain. ATPILOT's authentication, administrator system, subscriptions, private template storage, Compose UI, and detection state machine are newly implemented and are not a renamed copy of Smart AutoClicker.

If future work copies or modifies any upstream file, preserve its copyright headers, mark the file as third-party, publish the corresponding source under GPL-3.0, and update this notice with the exact file list and modifications. The complete license text is available from the Free Software Foundation:

https://www.gnu.org/licenses/gpl-3.0.txt

## Seed template source

The 16 seed PNGs under `app/src/main/assets/templates` were downloaded from:

https://github.com/aalamdiwan555-art/Detail-Report/tree/main/android-app/app/src/main/assets/templates

They are data assets, not Smart AutoClicker source code. Their inclusion is documented separately from the GPL-3.0 software notice above.

## AndroidX, Kotlin, Room, and Jetpack Compose

These libraries retain their own licenses and notices. They are consumed from Maven Central through Gradle and are not copied into this repository. See their respective artifact metadata for the exact license terms.