package com.jayathu.automata.scripts

import android.content.Context
import android.graphics.Rect
import android.util.Log
import com.jayathu.automata.engine.ActionExecutor
import com.jayathu.automata.engine.AutomationEngine
import com.jayathu.automata.engine.AutomationStep
import com.jayathu.automata.engine.NodeFinder
import com.jayathu.automata.engine.ScreenReader
import com.jayathu.automata.engine.StepResult
import com.jayathu.automata.service.AutomataAccessibilityService

/**
 * PickMe automation script using coordinate-based tapping + OCR.
 *
 * PickMe is a Flutter app with minimal accessibility, so we use:
 * - dispatchGesture() to tap at screen coordinates
 * - AccessibilityService.takeScreenshot() + ML Kit OCR to read text
 *
 * Actual PickMe flow:
 *   1. Home screen: shows map with "Where are you going?" search bar
 *   2. Tap search bar → PICKUP/DROP screen with:
 *      - PICKUP field (auto-filled with "Your Location")
 *      - DROP field (placeholder "Where are you going?")
 *      - Saved Addresses list
 *      - Keyboard visible
 *   3. Tap DROP field → type destination → search results appear
 *   4. Tap search result → map view with ride options panel
 *   5. Ride options panel shows vehicle types + prices + "Book Now"
 *   6. Read price (we do NOT book — only read for comparison)
 */
object PickMeScript {

    private const val TAG = "PickMeScript"
    private const val PACKAGE = "com.pickme.passenger"
    private const val SCREEN_CENTER_X = 540f

    fun buildSteps(context: Context, destination: String, rideType: String): List<AutomationStep> {
        return listOf(
            verifyAppInstalled(context),
            launchApp(context),
            waitForHomeScreen(),
            tapSearchBar(),
            tapDropFieldAndEnterDestination(destination),
            selectSearchResult(destination),
            waitForRideOptions(),
            readPriceViaOcr(rideType)
        )
    }

    fun buildBookingSteps(context: Context, destination: String, rideType: String): List<AutomationStep> {
        // PickMe remembers the last ride options screen, so just re-open and book
        return listOf(
            launchApp(context),
            waitForRideOptions(),
            selectRideType(rideType),
            tapBookNow(),
            tapConfirmPickup()
        )
    }

    private fun verifyAppInstalled(context: Context) = AutomationStep(
        name = "Verify PickMe installed",
        waitCondition = { true },
        timeoutMs = 2_000,
        action = { _, _ ->
            if (AutomationEngine.isAppInstalled(context, PACKAGE)) {
                StepResult.Success
            } else {
                StepResult.Failure("PickMe app is not installed")
            }
        }
    )

    private fun launchApp(context: Context) = AutomationStep(
        name = "Launch PickMe",
        waitCondition = { true },
        timeoutMs = 5_000,
        delayAfterMs = 3000,
        action = { _, _ ->
            if (AutomationEngine.launchApp(context, PACKAGE)) {
                StepResult.Success
            } else {
                StepResult.Failure("Failed to launch PickMe")
            }
        }
    )

    private fun waitForHomeScreen() = AutomationStep(
        name = "Wait for PickMe home screen",
        waitCondition = { root ->
            root.packageName?.toString() == PACKAGE
        },
        timeoutMs = 10_000,
        delayAfterMs = 1500,
        action = { _, _ ->
            Log.i(TAG, "PickMe home screen detected")
            StepResult.Success
        }
    )

    /**
     * Step 1: Tap "Where are you going?" on the home screen.
     * After tap, the PICKUP/DROP screen should appear (detected by "PICKUP" or "DROP" in OCR).
     */
    private fun tapSearchBar() = AutomationStep(
        name = "Tap search bar on home screen",
        waitCondition = { root ->
            root.packageName?.toString() == PACKAGE
        },
        timeoutMs = 15_000,
        delayAfterMs = 1500,
        maxRetries = 3,
        action = { _, _ ->
            val service = AutomataAccessibilityService.instance.value
                ?: return@AutomationStep StepResult.Failure("No accessibility service")

            // OCR to find "Where are you going?" on home screen
            val ocr = ScreenReader.captureAndRead(service)
            var tapY = 1163f
            if (ocr != null) {
                // Check if we're already on the PICKUP/DROP screen
                if (ocr.fullText.contains("PICKUP", ignoreCase = false) &&
                    ocr.fullText.contains("DROP", ignoreCase = false)) {
                    Log.i(TAG, "Already on PICKUP/DROP screen, skipping tap")
                    return@AutomationStep StepResult.Success
                }

                val blocks = ScreenReader.findTextBlocks(ocr, "Where are you going")
                if (blocks.isNotEmpty() && blocks.first().bounds != null) {
                    tapY = blocks.first().bounds!!.centerY().toFloat()
                    Log.i(TAG, "Found search bar via OCR at Y=$tapY")
                }
            }

            Log.i(TAG, "Tapping search bar at ($SCREEN_CENTER_X, $tapY)")
            ActionExecutor.tapAtCoordinates(service, SCREEN_CENTER_X, tapY)

            // Verify PICKUP/DROP screen appeared
            kotlinx.coroutines.delay(2000)
            val newOcr = ScreenReader.captureAndRead(service)
            if (newOcr != null) {
                Log.i(TAG, "After tap: ${newOcr.fullText.take(200)}")
                if (newOcr.fullText.contains("PICKUP", ignoreCase = false) ||
                    newOcr.fullText.contains("DROP", ignoreCase = false)) {
                    Log.i(TAG, "PICKUP/DROP screen opened!")
                    return@AutomationStep StepResult.Success
                }
            }

            StepResult.Retry("PICKUP/DROP screen not detected after tap")
        }
    )

    /**
     * Step 2: On the PICKUP/DROP screen, tap the DROP field and enter destination.
     * The DROP field shows "Where are you going?" as placeholder.
     * After tapping, the keyboard should be active. We try accessibility setText first,
     * then fall back to tapping individual keyboard characters.
     */
    private fun tapDropFieldAndEnterDestination(destination: String) = AutomationStep(
        name = "Enter destination in DROP field",
        waitCondition = { root ->
            root.packageName?.toString() == PACKAGE
        },
        timeoutMs = 15_000,
        delayAfterMs = 2500,
        maxRetries = 4,
        action = { root, _ ->
            val service = AutomataAccessibilityService.instance.value
                ?: return@AutomationStep StepResult.Failure("No accessibility service")

            // First, find and tap the DROP field via OCR
            val ocr = ScreenReader.captureAndRead(service)
            if (ocr != null) {
                Log.i(TAG, "PICKUP/DROP screen: ${ocr.fullText.take(300)}")

                // Find "DROP" text or "Where are you going?" text on this screen
                val dropBlocks = ScreenReader.findTextBlocks(ocr, "DROP")
                val whereBlocks = ScreenReader.findTextBlocks(ocr, "Where are you going")

                // Tap the DROP field area — it's next to the "DROP" label
                if (dropBlocks.isNotEmpty() && dropBlocks.first().bounds != null) {
                    val dropBounds = dropBlocks.first().bounds!!
                    // The "Where are you going?" input is to the right of "DROP" label
                    // Tap at center X of screen, at the DROP label's Y
                    val tapY = dropBounds.centerY().toFloat()
                    Log.i(TAG, "Tapping DROP field at ($SCREEN_CENTER_X, $tapY)")
                    ActionExecutor.tapAtCoordinates(service, SCREEN_CENTER_X, tapY)
                    kotlinx.coroutines.delay(800)
                } else if (whereBlocks.isNotEmpty() && whereBlocks.first().bounds != null) {
                    val b = whereBlocks.first().bounds!!
                    Log.i(TAG, "Tapping 'Where are you going?' at (${b.centerX()}, ${b.centerY()})")
                    ActionExecutor.tapAtCoordinates(service, b.centerX().toFloat(), b.centerY().toFloat())
                    kotlinx.coroutines.delay(800)
                }
            }

            // Find ALL editable fields — PICKUP is first, DROP is second
            val freshRoot = AutomataAccessibilityService.instance.value?.getRootNode() ?: root
            val allEditFields = NodeFinder.findAllNodesRecursive(freshRoot) { it.isEditable }
            Log.i(TAG, "Found ${allEditFields.size} editable field(s)")

            // We want the DROP field (second editable), not PICKUP (first)
            // The DROP field should be empty or have placeholder text
            val dropField = if (allEditFields.size >= 2) {
                Log.i(TAG, "Using second editable field (DROP)")
                allEditFields[1]
            } else if (allEditFields.size == 1) {
                // Only one field — check if it's empty (DROP) or filled (PICKUP)
                val field = allEditFields[0]
                val fieldText = field.text?.toString() ?: ""
                Log.i(TAG, "Only one editable field, text='$fieldText'")
                field
            } else {
                null
            }

            if (dropField != null) {
                Log.i(TAG, "Setting destination on DROP field")
                ActionExecutor.clearText(dropField)
                if (ActionExecutor.setText(dropField, destination)) {
                    Log.i(TAG, "Destination text set successfully")
                    kotlinx.coroutines.delay(2000)
                    val afterOcr = ScreenReader.captureAndRead(service)
                    if (afterOcr != null) {
                        Log.i(TAG, "After entering destination: ${afterOcr.fullText.take(300)}")
                    }
                    return@AutomationStep StepResult.Success
                }
            }

            Log.w(TAG, "No suitable editable field found for DROP")
            StepResult.Retry("Could not enter destination text")
        }
    )

    /**
     * Step 3: Select the first search result matching the destination.
     */
    private fun selectSearchResult(destination: String) = AutomationStep(
        name = "Select search result",
        waitCondition = { root ->
            root.packageName?.toString() == PACKAGE
        },
        timeoutMs = 10_000,
        delayAfterMs = 4000,
        maxRetries = 4,
        action = { _, _ ->
            val service = AutomataAccessibilityService.instance.value
                ?: return@AutomationStep StepResult.Failure("No accessibility service")

            Log.i(TAG, "Looking for search results via OCR...")

            // Use OCR to find the search result and tap it via coordinates
            // (accessibility clicks don't work reliably in Flutter)
            val ocr = ScreenReader.captureAndRead(service)
            if (ocr != null) {
                Log.i(TAG, "Search results OCR: ${ocr.fullText.take(400)}")

                // Look for destination name in the results area
                // Results appear below the PICKUP/DROP fields and above the keyboard
                val destWords = destination.split(" ").filter { it.length > 2 }
                for (word in destWords) {
                    val blocks = ScreenReader.findTextBlocks(ocr, word)
                    // Filter: must be in the results area (below search fields ~200, above keyboard ~1500)
                    // Also skip blocks that are part of the search field (contain "PICKUP" or "DROP" nearby)
                    val resultBlocks = blocks.filter { block ->
                        val top = block.bounds?.top ?: 0
                        // Must be below the DROP field and above keyboard
                        top in 700..1500 &&
                        // Must be a substantial text (not a single letter from keyboard)
                        block.text.length > 3
                    }
                    if (resultBlocks.isNotEmpty() && resultBlocks.first().bounds != null) {
                        val b = resultBlocks.first().bounds!!
                        Log.i(TAG, "Tapping search result containing '$word': '${resultBlocks.first().text}' at (${b.centerX()}, ${b.centerY()})")
                        ActionExecutor.tapAtCoordinates(service, SCREEN_CENTER_X, b.centerY().toFloat())
                        return@AutomationStep StepResult.Success
                    }
                }

                // Fallback: tap any substantial text block in the results area
                val resultBlock = ocr.blocks.find { block ->
                    val top = block.bounds?.top ?: 0
                    top in 700..1500 && block.text.length > 5 &&
                    !block.text.contains("PICKUP") && !block.text.contains("DROP") &&
                    !block.text.contains("Saved") && !block.text.contains("Set location") &&
                    !block.text.contains("Book for")
                }
                if (resultBlock?.bounds != null) {
                    val b = resultBlock.bounds!!
                    Log.i(TAG, "Tapping fallback result: '${resultBlock.text}' at ($SCREEN_CENTER_X, ${b.centerY()})")
                    ActionExecutor.tapAtCoordinates(service, SCREEN_CENTER_X, b.centerY().toFloat())
                    return@AutomationStep StepResult.Success
                }
            }

            StepResult.Retry("No search results found")
        }
    )

    /**
     * Step 4: Wait for ride options panel (map view with vehicle types + prices).
     */
    private fun waitForRideOptions() = AutomationStep(
        name = "Wait for ride options",
        waitCondition = { root ->
            root.packageName?.toString() == PACKAGE
        },
        timeoutMs = 20_000,
        delayAfterMs = 1500,
        maxRetries = 6,
        action = { _, _ ->
            val service = AutomataAccessibilityService.instance.value
                ?: return@AutomationStep StepResult.Failure("No accessibility service")

            val ocr = ScreenReader.captureAndRead(service)
            if (ocr != null) {
                Log.i(TAG, "Checking for ride options: ${ocr.fullText.take(400)}")

                val hasPrice = ocr.fullText.contains("Rs", ignoreCase = true) ||
                        ocr.fullText.contains("LKR", ignoreCase = true)
                val hasVehicle = ocr.fullText.contains("Bike", ignoreCase = true) ||
                        ocr.fullText.contains("Tuk", ignoreCase = true) ||
                        ocr.fullText.contains("Car", ignoreCase = true) ||
                        ocr.fullText.contains("Mini", ignoreCase = true) ||
                        ocr.fullText.contains("Nano", ignoreCase = true)
                val hasBookButton = ocr.fullText.contains("Book", ignoreCase = true)

                if (hasPrice || (hasVehicle && hasBookButton)) {
                    Log.i(TAG, "Ride options screen detected! hasPrice=$hasPrice hasVehicle=$hasVehicle hasBook=$hasBookButton")
                    return@AutomationStep StepResult.Success
                }

                Log.i(TAG, "Not ride options. hasPrice=$hasPrice hasVehicle=$hasVehicle hasBook=$hasBookButton")
            }

            StepResult.Retry("Ride options not visible yet")
        }
    )

    /**
     * Step 5: Read price from the ride options screen via OCR.
     * Finds all prices, picks the one closest to the requested ride type.
     */
    private fun readPriceViaOcr(rideType: String) = AutomationStep(
        name = "Read PickMe price ($rideType)",
        waitCondition = { root ->
            root.packageName?.toString() == PACKAGE
        },
        timeoutMs = 15_000,
        maxRetries = 5,
        delayBeforeMs = 500,
        action = { _, _ ->
            val service = AutomataAccessibilityService.instance.value
                ?: return@AutomationStep StepResult.Failure("No accessibility service")

            val ocr = ScreenReader.captureAndRead(service)
            if (ocr == null) {
                return@AutomationStep StepResult.Retry("Failed to capture screenshot")
            }

            Log.i(TAG, "Price reading OCR: ${ocr.fullText}")

            // Find all prices on screen
            val pricePattern = Regex("""(?:Rs\.?|LKR)\s*(\d[\d,.]*)""", RegexOption.IGNORE_CASE)
            val allPrices = mutableListOf<Pair<String, Rect?>>()

            for (block in ocr.blocks) {
                // Fix common OCR misreads in price text before matching
                val cleanedText = block.text
                    .replace("l", "1")  // lowercase L → 1
                    .replace("O", "0")  // uppercase O → 0
                    .replace("I", "1")  // uppercase I → 1
                val match = pricePattern.find(cleanedText)
                    ?: pricePattern.find(block.text) // Try original text too
                if (match != null) {
                    val price = match.groupValues[1].replace(",", "")
                    allPrices.add(price to block.bounds)
                    Log.i(TAG, "Found price: Rs $price at ${block.bounds} (raw: '${block.text}')")
                }
            }

            if (allPrices.isEmpty()) {
                val fullMatch = pricePattern.find(ocr.fullText)
                if (fullMatch != null) {
                    val price = fullMatch.groupValues[1].replace(",", "")
                    allPrices.add(price to null)
                    Log.i(TAG, "Found price in full text: Rs $price")
                }
            }

            if (allPrices.isNotEmpty()) {
                // Ride types are arranged HORIZONTALLY (left to right: Tuk, Bike, Flex/Car)
                // Match by X-distance (horizontal) not Y-distance
                val rideTypeBlocks = ScreenReader.findTextBlocks(ocr, rideType)
                val price = if (rideTypeBlocks.isNotEmpty() && rideTypeBlocks.first().bounds != null && allPrices.size > 1) {
                    val rideX = rideTypeBlocks.first().bounds!!.centerX()
                    Log.i(TAG, "Ride type '$rideType' found at X=$rideX, matching by horizontal distance")
                    allPrices.minByOrNull { (_, bounds) ->
                        if (bounds != null) kotlin.math.abs(bounds.centerX() - rideX) else Int.MAX_VALUE
                    }?.first ?: allPrices.first().first
                } else {
                    allPrices.first().first
                }

                Log.i(TAG, "PickMe price for $rideType: Rs $price")
                StepResult.SuccessWithData("pickme_price", price)
            } else {
                Log.w(TAG, "No prices found in OCR")
                StepResult.Retry("Could not extract price")
            }
        }
    )

    /**
     * Tap the ride type label (Tuk, Bike, Flex) on the ride options panel.
     */
    private fun selectRideType(rideType: String) = AutomationStep(
        name = "Select ride type: $rideType",
        waitCondition = { root ->
            root.packageName?.toString() == PACKAGE
        },
        timeoutMs = 10_000,
        delayAfterMs = 1500,
        maxRetries = 3,
        action = { _, _ ->
            val service = AutomataAccessibilityService.instance.value
                ?: return@AutomationStep StepResult.Failure("No accessibility service")

            val ocr = ScreenReader.captureAndRead(service)
            if (ocr != null) {
                val rideBlocks = ScreenReader.findTextBlocks(ocr, rideType)
                if (rideBlocks.isNotEmpty() && rideBlocks.first().bounds != null) {
                    val b = rideBlocks.first().bounds!!
                    Log.i(TAG, "Tapping ride type '$rideType' at (${b.centerX()}, ${b.centerY()})")
                    ActionExecutor.tapAtCoordinates(service, b.centerX().toFloat(), b.centerY().toFloat())
                    return@AutomationStep StepResult.Success
                }
            }

            StepResult.Retry("Could not find ride type '$rideType'")
        }
    )

    /**
     * Tap the "Book Now" button on the ride options panel.
     */
    private fun tapBookNow() = AutomationStep(
        name = "Tap Book Now",
        waitCondition = { root ->
            root.packageName?.toString() == PACKAGE
        },
        timeoutMs = 10_000,
        delayAfterMs = 2000,
        maxRetries = 3,
        action = { root, _ ->
            val service = AutomataAccessibilityService.instance.value
                ?: return@AutomationStep StepResult.Failure("No accessibility service")

            // Try accessibility first
            val bookNode = NodeFinder.findByText(root, "Book Now")
                ?: NodeFinder.findByText(root, "Book now")
            if (bookNode != null) {
                Log.i(TAG, "Tapping 'Book Now' via accessibility")
                if (ActionExecutor.click(bookNode)) {
                    return@AutomationStep StepResult.Success
                }
            }

            // OCR fallback
            val ocr = ScreenReader.captureAndRead(service)
            if (ocr != null) {
                val bookBlocks = ScreenReader.findTextBlocks(ocr, "Book Now")
                if (bookBlocks.isNotEmpty() && bookBlocks.first().bounds != null) {
                    val b = bookBlocks.first().bounds!!
                    Log.i(TAG, "Tapping 'Book Now' via OCR at (${b.centerX()}, ${b.centerY()})")
                    ActionExecutor.tapAtCoordinates(service, b.centerX().toFloat(), b.centerY().toFloat())
                    return@AutomationStep StepResult.Success
                }
            }

            StepResult.Retry("Could not find 'Book Now' button")
        }
    )

    /**
     * Tap the "Confirm pickup" button that appears after tapping "Book Now".
     */
    private fun tapConfirmPickup() = AutomationStep(
        name = "Tap Confirm Pickup",
        waitCondition = { root ->
            root.packageName?.toString() == PACKAGE
        },
        timeoutMs = 15_000,
        delayAfterMs = 2000,
        maxRetries = 5,
        action = { root, _ ->
            val service = AutomataAccessibilityService.instance.value
                ?: return@AutomationStep StepResult.Failure("No accessibility service")

            // Try accessibility first
            val confirmNode = NodeFinder.findByText(root, "Confirm pickup")
                ?: NodeFinder.findByText(root, "Confirm Pickup")
                ?: NodeFinder.findByText(root, "Confirm")
            if (confirmNode != null) {
                Log.i(TAG, "Tapping 'Confirm pickup' via accessibility")
                if (ActionExecutor.click(confirmNode)) {
                    return@AutomationStep StepResult.Success
                }
            }

            // OCR fallback — look for "CONFIRM PICKUP" button (all caps),
            // NOT "Confirm your pickup" (title text)
            val ocr = ScreenReader.captureAndRead(service)
            if (ocr != null) {
                Log.i(TAG, "Confirm screen OCR: ${ocr.fullText.take(300)}")

                // Prefer the all-caps "CONFIRM PICKUP" button (bottommost match)
                val blocks = ScreenReader.findTextBlocks(ocr, "CONFIRM PICKUP")
                    .ifEmpty { ScreenReader.findTextBlocks(ocr, "Confirm") }
                    .sortedByDescending { it.bounds?.top ?: 0 } // Bottom-most = button
                if (blocks.isNotEmpty() && blocks.first().bounds != null) {
                    val b = blocks.first().bounds!!
                    Log.i(TAG, "Tapping 'CONFIRM PICKUP' via OCR at (${b.centerX()}, ${b.centerY()})")
                    ActionExecutor.tapAtCoordinates(service, b.centerX().toFloat(), b.centerY().toFloat())
                    return@AutomationStep StepResult.Success
                }
            }

            StepResult.Retry("Could not find 'Confirm pickup' button")
        }
    )
}
