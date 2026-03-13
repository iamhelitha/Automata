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

    /**
     * Fix OCR-dropped decimal points in prices.
     * Sri Lankan ride prices are typically 100–9999 LKR with 2 decimal places.
     * If OCR drops the dot (e.g. "63969" instead of "639.69"), detect and fix it.
     */
    private fun normalizePrice(price: String): String {
        if (price.contains(".")) return price

        val value = price.toDoubleOrNull() ?: return price

        if (value >= 10000 && price.length >= 5) {
            val corrected = price.substring(0, price.length - 2) + "." + price.substring(price.length - 2)
            Log.i(TAG, "Price normalization: '$price' → '$corrected' (likely dropped decimal)")
            return corrected
        }

        return price
    }

    /**
     * Map generic ride type names to PickMe-specific names.
     * PickMe uses "Bike" for motorcycle rides, while Uber uses "Moto".
     */
    private fun mapRideType(rideType: String): String {
        return when (rideType.lowercase()) {
            "moto", "bike", "motorcycle" -> "Bike"
            "tuk", "tuktuk", "three-wheeler" -> "Tuk"
            "car", "flex", "mini", "nano" -> rideType // Keep as-is for PickMe
            else -> rideType
        }
    }

    fun buildSteps(context: Context, destination: String, rideType: String, pickupAddress: String = ""): List<AutomationStep> {
        val mappedType = mapRideType(rideType)
        val steps = mutableListOf(
            verifyAppInstalled(context),
            launchApp(context),
            waitForHomeScreen(),
            tapSearchBar()
        )

        // Set pickup location if provided
        if (pickupAddress.isNotBlank()) {
            steps.add(tapPickupFieldAndEnterAddress(pickupAddress))
            steps.add(selectPickupSearchResult(pickupAddress))
        }

        steps.addAll(listOf(
            tapDropFieldAndEnterDestination(destination),
            selectSearchResult(destination),
            waitForRideOptions(),
            readPriceViaOcr(mappedType)
        ))

        return steps
    }

    fun buildBookingSteps(context: Context, destination: String, rideType: String, pickupAddress: String = ""): List<AutomationStep> {
        val mappedType = mapRideType(rideType)
        // After force-close, PickMe starts from the home screen.
        // ensureOnRideOptionsScreen handles both cases: if already on ride options, it skips;
        // if on home screen, it navigates through the full search flow.
        return listOf(
            launchApp(context),
            ensureOnRideOptionsScreen(destination, pickupAddress),
            selectRideType(mappedType),
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
     * Tap the PICKUP field (first editable), clear "Your Location", enter pickup address.
     * On the PICKUP/DROP screen, PICKUP is the first editable field.
     */
    private fun tapPickupFieldAndEnterAddress(pickupAddress: String) = AutomationStep(
        name = "Enter pickup address",
        waitCondition = { root ->
            root.packageName?.toString() == PACKAGE
        },
        timeoutMs = 15_000,
        delayAfterMs = 2500,
        maxRetries = 4,
        action = { root, _ ->
            val service = AutomataAccessibilityService.instance.value
                ?: return@AutomationStep StepResult.Failure("No accessibility service")

            // Tap the PICKUP field area via OCR
            val ocr = ScreenReader.captureAndRead(service)
            if (ocr != null) {
                val pickupBlocks = ScreenReader.findTextBlocks(ocr, "PICKUP")
                val yourLocationBlocks = ScreenReader.findTextBlocks(ocr, "Your Location")

                if (pickupBlocks.isNotEmpty() && pickupBlocks.first().bounds != null) {
                    val b = pickupBlocks.first().bounds!!
                    Log.i(TAG, "Tapping PICKUP field at ($SCREEN_CENTER_X, ${b.centerY()})")
                    ActionExecutor.tapAtCoordinates(service, SCREEN_CENTER_X, b.centerY().toFloat())
                    kotlinx.coroutines.delay(800)
                } else if (yourLocationBlocks.isNotEmpty() && yourLocationBlocks.first().bounds != null) {
                    val b = yourLocationBlocks.first().bounds!!
                    Log.i(TAG, "Tapping 'Your Location' at (${b.centerX()}, ${b.centerY()})")
                    ActionExecutor.tapAtCoordinates(service, b.centerX().toFloat(), b.centerY().toFloat())
                    kotlinx.coroutines.delay(800)
                }
            }

            // Find the PICKUP field (first editable field)
            val freshRoot = AutomataAccessibilityService.instance.value?.getRootNode() ?: root
            val allEditFields = NodeFinder.findAllNodesRecursive(freshRoot) { it.isEditable }
            Log.i(TAG, "Found ${allEditFields.size} editable field(s) for pickup")

            val pickupField = allEditFields.firstOrNull()
            if (pickupField != null) {
                val fieldText = pickupField.text?.toString() ?: ""
                Log.i(TAG, "Pickup field text: '$fieldText'")
                pickupField.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_FOCUS)
                ActionExecutor.clearText(pickupField)
                if (ActionExecutor.setText(pickupField, pickupAddress)) {
                    Log.i(TAG, "Pickup address set: $pickupAddress")
                    kotlinx.coroutines.delay(2000)
                    return@AutomationStep StepResult.Success
                }
            }

            StepResult.Retry("Could not enter pickup address")
        }
    )

    /**
     * Select pickup search result from the list.
     */
    private fun selectPickupSearchResult(pickupAddress: String) = AutomationStep(
        name = "Select pickup result",
        waitCondition = { root ->
            root.packageName?.toString() == PACKAGE
        },
        timeoutMs = 10_000,
        delayAfterMs = 3000,
        maxRetries = 4,
        action = { _, _ ->
            val service = AutomataAccessibilityService.instance.value
                ?: return@AutomationStep StepResult.Failure("No accessibility service")

            val ocr = ScreenReader.captureAndRead(service)
            if (ocr != null) {
                Log.i(TAG, "Pickup search results OCR: ${ocr.fullText.take(400)}")

                val skipTexts = setOf("PICKUP", "DROP", "Saved", "Set location",
                    "Book for", "One way", "Return trip", "Skip", "Done", "GIF",
                    "English", "Your Location", "Where are you going")
                val resultCandidates = ocr.blocks
                    .filter { block ->
                        val top = block.bounds?.top ?: 0
                        top in 700..1500 &&
                        block.text.length > 3 &&
                        skipTexts.none { block.text.contains(it, ignoreCase = true) }
                    }
                    .sortedBy { it.bounds?.top ?: Int.MAX_VALUE }

                // Match by pickup address words
                val pickupWords = pickupAddress.split(" ").filter { it.length > 2 }
                for (word in pickupWords) {
                    val match = resultCandidates.find { it.text.contains(word, ignoreCase = true) }
                    if (match?.bounds != null) {
                        val b = match.bounds!!
                        Log.i(TAG, "Tapping pickup result matching '$word': '${match.text}' at ($SCREEN_CENTER_X, ${b.centerY()})")
                        ActionExecutor.tapAtCoordinates(service, SCREEN_CENTER_X, b.centerY().toFloat())
                        return@AutomationStep StepResult.Success
                    }
                }

                // Fallback: first result
                val firstResult = resultCandidates.firstOrNull()
                if (firstResult?.bounds != null) {
                    val b = firstResult.bounds!!
                    Log.i(TAG, "Tapping first pickup result: '${firstResult.text}' at ($SCREEN_CENTER_X, ${b.centerY()})")
                    ActionExecutor.tapAtCoordinates(service, SCREEN_CENTER_X, b.centerY().toFloat())
                    return@AutomationStep StepResult.Success
                }
            }

            StepResult.Retry("No pickup search results found")
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

                // Filter to blocks in the results area (below DROP field, above keyboard)
                // Skip UI elements like PICKUP, DROP, Saved, Set location, keyboard keys, etc.
                val skipTexts = setOf("PICKUP", "DROP", "Saved", "Set location",
                    "Book for", "One way", "Return trip", "Skip", "Done", "GIF",
                    "English", "Your Location", "Where are you going")
                val resultCandidates = ocr.blocks
                    .filter { block ->
                        val top = block.bounds?.top ?: 0
                        top in 700..1500 &&
                        block.text.length > 3 &&
                        skipTexts.none { block.text.contains(it, ignoreCase = true) }
                    }
                    .sortedBy { it.bounds?.top ?: Int.MAX_VALUE }

                // Try matching by destination words first
                val destWords = destination.split(" ").filter { it.length > 2 }
                for (word in destWords) {
                    val match = resultCandidates.find { it.text.contains(word, ignoreCase = true) }
                    if (match?.bounds != null) {
                        val b = match.bounds!!
                        Log.i(TAG, "Tapping result matching '$word': '${match.text}' at ($SCREEN_CENTER_X, ${b.centerY()})")
                        ActionExecutor.tapAtCoordinates(service, SCREEN_CENTER_X, b.centerY().toFloat())
                        return@AutomationStep StepResult.Success
                    }
                }

                // Fallback: tap the FIRST result in the list area
                // This handles short/generic destinations like "Home"
                val firstResult = resultCandidates.firstOrNull()
                if (firstResult?.bounds != null) {
                    val b = firstResult.bounds!!
                    Log.i(TAG, "Tapping first result: '${firstResult.text}' at ($SCREEN_CENTER_X, ${b.centerY()})")
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
     * Navigate to ride options screen for booking.
     * If already on ride options (prices + vehicle types visible), skip.
     * If on home screen (after force-close), navigate: tap search → enter pickup → enter destination → select result → wait for ride options.
     */
    private fun ensureOnRideOptionsScreen(destination: String, pickupAddress: String) = AutomationStep(
        name = "Navigate to ride options",
        waitCondition = { root ->
            root.packageName?.toString() == PACKAGE
        },
        timeoutMs = 45_000,
        maxRetries = 2,
        delayAfterMs = 1500,
        action = { root, _ ->
            val service = AutomataAccessibilityService.instance.value
                ?: return@AutomationStep StepResult.Failure("No accessibility service")

            // Check if already on ride options screen
            val ocr = ScreenReader.captureAndRead(service)
            if (ocr != null) {
                val hasPrice = ocr.fullText.contains("Rs", true) || ocr.fullText.contains("LKR", true)
                val hasVehicle = ocr.fullText.contains("Bike", true) ||
                        ocr.fullText.contains("Tuk", true) ||
                        ocr.fullText.contains("Car", true) ||
                        ocr.fullText.contains("Mini", true)
                val hasBookButton = ocr.fullText.contains("Book", true)
                if (hasPrice || (hasVehicle && hasBookButton)) {
                    Log.i(TAG, "Already on ride options screen")
                    return@AutomationStep StepResult.Success
                }
                Log.i(TAG, "Not on ride options, navigating from home screen...")
                Log.i(TAG, "Current screen: ${ocr.fullText.take(200)}")
            }

            // Step 1: Tap "Where are you going?" search bar
            var searchTapY = 1163f
            if (ocr != null) {
                // Check if already on PICKUP/DROP screen
                if (ocr.fullText.contains("PICKUP", ignoreCase = false) &&
                    ocr.fullText.contains("DROP", ignoreCase = false)) {
                    Log.i(TAG, "Already on PICKUP/DROP screen, skipping search bar tap")
                } else {
                    val blocks = ScreenReader.findTextBlocks(ocr, "Where are you going")
                    if (blocks.isNotEmpty() && blocks.first().bounds != null) {
                        searchTapY = blocks.first().bounds!!.centerY().toFloat()
                    }
                    Log.i(TAG, "Tapping search bar at ($SCREEN_CENTER_X, $searchTapY)")
                    ActionExecutor.tapAtCoordinates(service, SCREEN_CENTER_X, searchTapY)
                    kotlinx.coroutines.delay(2000)
                }
            } else {
                ActionExecutor.tapAtCoordinates(service, SCREEN_CENTER_X, searchTapY)
                kotlinx.coroutines.delay(2000)
            }

            // Step 2: Enter pickup address if provided
            if (pickupAddress.isNotBlank()) {
                val freshRoot1 = service.getRootNode() ?: root
                val pickupOcr = ScreenReader.captureAndRead(service)
                if (pickupOcr != null) {
                    val pickupBlocks = ScreenReader.findTextBlocks(pickupOcr, "PICKUP")
                    val yourLocBlocks = ScreenReader.findTextBlocks(pickupOcr, "Your Location")
                    if (pickupBlocks.isNotEmpty() && pickupBlocks.first().bounds != null) {
                        val b = pickupBlocks.first().bounds!!
                        ActionExecutor.tapAtCoordinates(service, SCREEN_CENTER_X, b.centerY().toFloat())
                        kotlinx.coroutines.delay(800)
                    } else if (yourLocBlocks.isNotEmpty() && yourLocBlocks.first().bounds != null) {
                        val b = yourLocBlocks.first().bounds!!
                        ActionExecutor.tapAtCoordinates(service, b.centerX().toFloat(), b.centerY().toFloat())
                        kotlinx.coroutines.delay(800)
                    }
                }

                val fields1 = NodeFinder.findAllNodesRecursive(freshRoot1) { it.isEditable }
                val pickupField = fields1.firstOrNull()
                if (pickupField != null) {
                    Log.i(TAG, "Entering pickup: $pickupAddress")
                    pickupField.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_FOCUS)
                    ActionExecutor.clearText(pickupField)
                    ActionExecutor.setText(pickupField, pickupAddress)
                    kotlinx.coroutines.delay(2000)

                    // Select pickup result
                    val pickupResultOcr = ScreenReader.captureAndRead(service)
                    if (pickupResultOcr != null) {
                        val pickupWords = pickupAddress.split(" ").filter { it.length > 2 }
                        val skipTexts = setOf("PICKUP", "DROP", "Saved", "Set location",
                            "Book for", "One way", "Return trip", "Your Location", "Where are you going")
                        val candidates = pickupResultOcr.blocks.filter { block ->
                            val top = block.bounds?.top ?: 0
                            top in 700..1500 && block.text.length > 3 &&
                            skipTexts.none { block.text.contains(it, true) }
                        }.sortedBy { it.bounds?.top ?: Int.MAX_VALUE }

                        val match = pickupWords.firstNotNullOfOrNull { word ->
                            candidates.find { it.text.contains(word, true) }
                        } ?: candidates.firstOrNull()

                        if (match?.bounds != null) {
                            Log.i(TAG, "Selecting pickup result: '${match.text}'")
                            ActionExecutor.tapAtCoordinates(service, SCREEN_CENTER_X, match.bounds!!.centerY().toFloat())
                            kotlinx.coroutines.delay(3000)
                        }
                    }
                }
            }

            // Step 3: Enter destination in DROP field
            val freshRoot2 = service.getRootNode() ?: root
            // Tap DROP field area first
            val dropOcr = ScreenReader.captureAndRead(service)
            if (dropOcr != null) {
                val dropBlocks = ScreenReader.findTextBlocks(dropOcr, "DROP")
                val whereBlocks = ScreenReader.findTextBlocks(dropOcr, "Where are you going")
                if (dropBlocks.isNotEmpty() && dropBlocks.first().bounds != null) {
                    val b = dropBlocks.first().bounds!!
                    ActionExecutor.tapAtCoordinates(service, SCREEN_CENTER_X, b.centerY().toFloat())
                    kotlinx.coroutines.delay(800)
                } else if (whereBlocks.isNotEmpty() && whereBlocks.first().bounds != null) {
                    val b = whereBlocks.first().bounds!!
                    ActionExecutor.tapAtCoordinates(service, b.centerX().toFloat(), b.centerY().toFloat())
                    kotlinx.coroutines.delay(800)
                }
            }

            val fields2 = NodeFinder.findAllNodesRecursive(freshRoot2) { it.isEditable }
            val dropField = if (fields2.size >= 2) {
                fields2[1] // DROP is second field
            } else {
                fields2.firstOrNull()
            }

            if (dropField != null) {
                Log.i(TAG, "Entering destination: $destination")
                ActionExecutor.clearText(dropField)
                ActionExecutor.setText(dropField, destination)
                kotlinx.coroutines.delay(2000)
            } else {
                Log.w(TAG, "No editable field found for destination")
                return@AutomationStep StepResult.Retry("No destination field found")
            }

            // Step 4: Select destination result
            val destOcr = ScreenReader.captureAndRead(service)
            if (destOcr != null) {
                Log.i(TAG, "Destination results: ${destOcr.fullText.take(400)}")
                val destWords = destination.split(" ").filter { it.length > 2 }
                val skipTexts = setOf("PICKUP", "DROP", "Saved", "Set location",
                    "Book for", "One way", "Return trip", "Your Location", "Where are you going")
                val candidates = destOcr.blocks.filter { block ->
                    val top = block.bounds?.top ?: 0
                    top in 700..1500 && block.text.length > 3 &&
                    skipTexts.none { block.text.contains(it, true) }
                }.sortedBy { it.bounds?.top ?: Int.MAX_VALUE }

                val match = destWords.firstNotNullOfOrNull { word ->
                    candidates.find { it.text.contains(word, true) }
                } ?: candidates.firstOrNull()

                if (match?.bounds != null) {
                    Log.i(TAG, "Selecting destination result: '${match.text}'")
                    ActionExecutor.tapAtCoordinates(service, SCREEN_CENTER_X, match.bounds!!.centerY().toFloat())
                } else {
                    Log.w(TAG, "No destination results found")
                    return@AutomationStep StepResult.Retry("No destination results")
                }
            }

            // Step 5: Wait for ride options to load
            kotlinx.coroutines.delay(4000)
            val finalOcr = ScreenReader.captureAndRead(service)
            if (finalOcr != null) {
                val hasPrice = finalOcr.fullText.contains("Rs", true) || finalOcr.fullText.contains("LKR", true)
                val hasVehicle = finalOcr.fullText.contains("Bike", true) ||
                        finalOcr.fullText.contains("Tuk", true) ||
                        finalOcr.fullText.contains("Car", true) ||
                        finalOcr.fullText.contains("Mini", true)
                val hasBookButton = finalOcr.fullText.contains("Book", true)
                if (hasPrice || (hasVehicle && hasBookButton)) {
                    Log.i(TAG, "Ride options loaded after navigation")
                    return@AutomationStep StepResult.Success
                }
                Log.i(TAG, "Final screen: ${finalOcr.fullText.take(200)}")
            }

            StepResult.Retry("Ride options not loaded after navigation")
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

            // Find all prices on screen using findAll (handles multiple prices per block)
            val pricePattern = Regex("""(?:Rs\.?|LKR)\s*(\d[\d,.]*)""", RegexOption.IGNORE_CASE)
            val allPrices = mutableListOf<Pair<String, Rect?>>()

            for (block in ocr.blocks) {
                // Fix common OCR misreads in price text before matching
                val cleanedText = block.text
                    .replace("l", "1")  // lowercase L → 1
                    .replace("O", "0")  // uppercase O → 0
                    .replace("I", "1")  // uppercase I → 1
                val matches = pricePattern.findAll(cleanedText).toList()
                    .ifEmpty { pricePattern.findAll(block.text).toList() }

                if (matches.size == 1) {
                    // Single price in block — use block bounds directly
                    val rawPrice = matches[0].groupValues[1].replace(",", "")
                    val price = normalizePrice(rawPrice)
                    allPrices.add(price to block.bounds)
                    Log.i(TAG, "Found price: Rs $price at ${block.bounds} (raw: '${block.text}')")
                } else if (matches.size > 1 && block.bounds != null) {
                    // Multiple prices in one block — estimate X position for each
                    // based on character offset within the block text
                    val b = block.bounds!!
                    val textLen = cleanedText.length
                    for (m in matches) {
                        val rawPrice = m.groupValues[1].replace(",", "")
                        val price = normalizePrice(rawPrice)
                        val charPos = m.range.first
                        val estimatedX = b.left + (charPos.toFloat() / textLen * b.width()).toInt()
                        val estimatedBounds = Rect(estimatedX - 30, b.top, estimatedX + 30, b.bottom)
                        allPrices.add(price to estimatedBounds)
                        Log.i(TAG, "Found price: Rs $price at estimated X=$estimatedX (raw: '${block.text}', charPos=$charPos)")
                    }
                }
            }

            if (allPrices.isEmpty()) {
                val fullMatches = pricePattern.findAll(ocr.fullText).toList()
                for (m in fullMatches) {
                    val rawPrice = m.groupValues[1].replace(",", "")
                    val price = normalizePrice(rawPrice)
                    allPrices.add(price to null)
                    Log.i(TAG, "Found price in full text: Rs $price")
                }
            }

            if (allPrices.isNotEmpty()) {
                // Find all ride type labels and their X positions for order-based matching
                val rideTypeLabels = listOf("Tuk", "Bike", "Flex", "Car", "Mini", "Nano")
                val rideTypePositions = mutableListOf<Pair<String, Int>>()
                for (rt in rideTypeLabels) {
                    val blocks = ScreenReader.findTextBlocks(ocr, rt)
                    if (blocks.isNotEmpty() && blocks.first().bounds != null) {
                        val b = blocks.first().bounds!!
                        rideTypePositions.add(rt to b.centerX())
                        Log.i(TAG, "Ride type '$rt' at X=${b.centerX()}, Y=${b.centerY()}")
                    }
                }
                rideTypePositions.sortBy { it.second } // left to right

                // Sort prices by X position
                val pricesWithBounds = allPrices.filter { it.second != null }
                    .sortedBy { it.second!!.centerX() }

                // Strategy 1: Order-based matching (most reliable for PickMe layout)
                // Match ride types and prices by their left-to-right order
                val targetIdx = rideTypePositions.indexOfFirst { it.first.equals(rideType, true) }
                val price = if (targetIdx >= 0 && targetIdx < pricesWithBounds.size) {
                    Log.i(TAG, "Order-based match: '$rideType' is at index $targetIdx of ${rideTypePositions.map { it.first }}")
                    Log.i(TAG, "Prices in order: ${pricesWithBounds.map { "Rs ${it.first} (X=${it.second?.centerX()})" }}")
                    pricesWithBounds[targetIdx].first
                } else if (targetIdx >= 0 && rideTypePositions.size > 0) {
                    // Fallback: use combined distance matching
                    val targetX = rideTypePositions[targetIdx].second
                    Log.i(TAG, "Fallback distance match for '$rideType' at X=$targetX")
                    for ((p, bounds) in allPrices) {
                        if (bounds != null) {
                            Log.i(TAG, "  Price Rs $p: X=${bounds.centerX()}, dx=${kotlin.math.abs(bounds.centerX() - targetX)}")
                        }
                    }
                    allPrices.minByOrNull { (_, bounds) ->
                        if (bounds != null) kotlin.math.abs(bounds.centerX() - targetX) else Int.MAX_VALUE
                    }?.first ?: allPrices.first().first
                } else {
                    Log.w(TAG, "Ride type '$rideType' NOT found in OCR, using first price")
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
