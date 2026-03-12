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
import com.jayathu.automata.engine.UiInspector
import com.jayathu.automata.service.AutomataAccessibilityService

/**
 * Uber automation script — hybrid accessibility + OCR approach.
 *
 * Uber flow:
 *   1. Launch Uber → may show "ride again" prompt → tap Skip
 *   2. Home screen → tap "Where to?" search bar
 *   3. Search screen → starting location already filled, enter destination in "Where to" field
 *      (or tap from history below)
 *   4. Select search result → ride options screen
 *   5. Ride types: Tuk, Moto, Zip
 *   6. Read price (do NOT book)
 */
object UberScript {

    private const val TAG = "UberScript"
    private const val PACKAGE = "com.ubercab"

    // Text labels
    private const val WHERE_TO_TEXT = "Where to?"

    // Uber ride type names
    private const val TUK_TEXT = "Tuk"
    private const val MOTO_TEXT = "Moto"
    private const val ZIP_TEXT = "Zip"

    fun buildSteps(context: Context, destination: String, rideType: String): List<AutomationStep> {
        return listOf(
            verifyAppInstalled(context),
            launchApp(context),
            handleSkipPrompt(),
            tapWhereToField(),
            enterDestination(destination),
            selectSearchResult(destination),
            waitForRideOptions(),
            readPrice(mapRideType(rideType))
        )
    }

    fun buildBookingSteps(context: Context, destination: String, rideType: String): List<AutomationStep> {
        // Uber remembers the last ride options screen, so just re-open and book
        return listOf(
            launchApp(context),
            waitForRideOptions(),
            selectRideType(mapRideType(rideType)),
            tapChooseRide(mapRideType(rideType))
        )
    }

    private fun mapRideType(rideType: String): String {
        return when (rideType.lowercase()) {
            "bike", "moto" -> MOTO_TEXT
            "tuk" -> TUK_TEXT
            "car", "zip" -> ZIP_TEXT
            else -> rideType
        }
    }

    private fun verifyAppInstalled(context: Context) = AutomationStep(
        name = "Verify Uber installed",
        waitCondition = { true },
        timeoutMs = 2_000,
        action = { _, _ ->
            if (AutomationEngine.isAppInstalled(context, PACKAGE)) {
                StepResult.Success
            } else {
                StepResult.Failure("Uber app is not installed")
            }
        }
    )

    private fun launchApp(context: Context) = AutomationStep(
        name = "Launch Uber",
        waitCondition = { true },
        timeoutMs = 5_000,
        delayAfterMs = 3000,
        action = { _, _ ->
            if (AutomationEngine.launchApp(context, PACKAGE)) {
                StepResult.Success
            } else {
                StepResult.Failure("Failed to launch Uber")
            }
        }
    )

    /**
     * Handle the "ride again with previous driver" prompt if it appears.
     * Tap "Skip" or similar dismiss button. If no prompt, just proceed.
     */
    private fun handleSkipPrompt() = AutomationStep(
        name = "Handle skip prompt (if any)",
        waitCondition = { root ->
            root.packageName?.toString() == PACKAGE
        },
        timeoutMs = 5_000,
        delayAfterMs = 1500,
        action = { root, _ ->
            val service = AutomataAccessibilityService.instance.value
                ?: return@AutomationStep StepResult.Failure("No accessibility service")

            // Check if there's a "Skip" or dismiss button via accessibility
            val skipNode = NodeFinder.findByText(root, "Skip")
                ?: NodeFinder.findByText(root, "Not now")
                ?: NodeFinder.findByText(root, "Dismiss")
                ?: NodeFinder.findByContentDescription(root, "Close")

            if (skipNode != null) {
                Log.i(TAG, "Found skip/dismiss button: ${skipNode.text ?: skipNode.contentDescription}")
                ActionExecutor.click(skipNode)
                return@AutomationStep StepResult.Success
            }

            // OCR fallback
            val ocr = ScreenReader.captureAndRead(service)
            if (ocr != null) {
                Log.i(TAG, "Initial screen OCR: ${ocr.fullText.take(300)}")
                val skipBlocks = ScreenReader.findTextBlocks(ocr, "Skip")
                if (skipBlocks.isNotEmpty() && skipBlocks.first().bounds != null) {
                    val b = skipBlocks.first().bounds!!
                    Log.i(TAG, "Tapping 'Skip' via OCR at (${b.centerX()}, ${b.centerY()})")
                    ActionExecutor.tapAtCoordinates(service, b.centerX().toFloat(), b.centerY().toFloat())
                    return@AutomationStep StepResult.Success
                }

                // No skip prompt — check if we're already on the home screen
                if (ocr.fullText.contains("Where to", ignoreCase = true) ||
                    ocr.fullText.contains("Where to?", ignoreCase = true)) {
                    Log.i(TAG, "No skip prompt, already on home screen")
                    return@AutomationStep StepResult.Skip("No prompt to skip")
                }
            }

            // No prompt found, proceed anyway
            Log.i(TAG, "No skip prompt detected, proceeding")
            StepResult.Skip("No skip prompt")
        }
    )

    private fun tapWhereToField() = AutomationStep(
        name = "Tap 'Where to?' field",
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
            val node = NodeFinder.findByText(root, WHERE_TO_TEXT)
                ?: NodeFinder.findByText(root, "Search destination")
                ?: NodeFinder.findByContentDescription(root, WHERE_TO_TEXT)

            if (node != null) {
                Log.i(TAG, "Found 'Where to?' via accessibility, clicking")
                if (ActionExecutor.click(node)) {
                    return@AutomationStep StepResult.Success
                }
            }

            // OCR fallback
            val ocr = ScreenReader.captureAndRead(service)
            if (ocr != null) {
                Log.i(TAG, "Home screen OCR: ${ocr.fullText.take(300)}")
                val blocks = ScreenReader.findTextBlocks(ocr, "Where to")
                if (blocks.isNotEmpty() && blocks.first().bounds != null) {
                    val b = blocks.first().bounds!!
                    Log.i(TAG, "Tapping 'Where to?' via OCR at (${b.centerX()}, ${b.centerY()})")
                    ActionExecutor.tapAtCoordinates(service, b.centerX().toFloat(), b.centerY().toFloat())
                    return@AutomationStep StepResult.Success
                }
            }

            StepResult.Retry("Could not find 'Where to?' field")
        }
    )

    private fun enterDestination(destination: String) = AutomationStep(
        name = "Enter destination: $destination",
        waitCondition = { root ->
            NodeFinder.hasNode(root) { it.isEditable }
        },
        timeoutMs = 10_000,
        delayAfterMs = 2000,
        maxRetries = 3,
        action = { root, _ ->
            // Find ALL editable fields — destination is the empty one
            val allEditFields = NodeFinder.findAllNodesRecursive(root) { it.isEditable }
            Log.i(TAG, "Found ${allEditFields.size} editable field(s)")
            for ((i, field) in allEditFields.withIndex()) {
                Log.i(TAG, "  Field $i: text='${field.text}' hint='${field.hintText}'")
            }

            // Find the destination field: prefer empty/placeholder, or the last one
            val destField = if (allEditFields.size >= 2) {
                val emptyField = allEditFields.find { field ->
                    val text = field.text?.toString() ?: ""
                    text.isEmpty() || text.contains("Where to", ignoreCase = true) ||
                    text.contains("Search", ignoreCase = true) ||
                    text.contains("destination", ignoreCase = true)
                }
                if (emptyField != null) {
                    Log.i(TAG, "Using empty/placeholder field as destination")
                    emptyField
                } else {
                    Log.i(TAG, "Using last editable field as destination")
                    allEditFields.last()
                }
            } else {
                allEditFields.firstOrNull()
            }

            if (destField != null) {
                // Focus the field first
                destField.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_FOCUS)
                ActionExecutor.clearText(destField)
                if (ActionExecutor.setText(destField, destination)) {
                    Log.i(TAG, "Destination text set: $destination")
                    // Wait for search results
                    kotlinx.coroutines.delay(1500)
                    return@AutomationStep StepResult.Success
                }
            }

            StepResult.Retry("No editable field found for destination")
        }
    )

    private fun selectSearchResult(destination: String) = AutomationStep(
        name = "Select search result",
        waitCondition = { root ->
            root.packageName?.toString() == PACKAGE
        },
        timeoutMs = 10_000,
        delayAfterMs = 3000,
        maxRetries = 4,
        action = { root, _ ->
            val service = AutomataAccessibilityService.instance.value
                ?: return@AutomationStep StepResult.Failure("No accessibility service")

            Log.i(TAG, "Looking for search results...")

            val destWords = destination.split(" ").filter { it.length > 2 }

            // Try accessibility — find clickable, NON-editable results matching destination
            // (editable nodes are the search input fields, not results)
            val clickableResults = NodeFinder.findAllNodesRecursive(root) {
                it.isClickable && !it.isEditable && it.text != null && it.text.toString().length > 3
            }

            for (node in clickableResults) {
                Log.i(TAG, "Clickable node: '${node.text}' editable=${node.isEditable}")
            }

            val matchingResult = clickableResults.find { node ->
                val text = node.text.toString()
                destWords.any { word -> text.contains(word, ignoreCase = true) } &&
                // Must not be a search field hint
                !text.equals("Where to?", ignoreCase = true)
            }

            if (matchingResult != null) {
                Log.i(TAG, "Clicking matching result via accessibility: ${matchingResult.text}")
                if (ActionExecutor.click(matchingResult)) {
                    return@AutomationStep StepResult.Success
                }
            }

            // OCR approach — tap the search result by coordinates.
            // IMPORTANT: The search input field also contains the destination text,
            // so we must tap the RESULT row (which has an address line like
            // "6.9 mi 72 Bauddhaloka Mawatha, Colombo"), not the input field.
            val ocr = ScreenReader.captureAndRead(service)
            if (ocr != null) {
                Log.i(TAG, "Search results OCR: ${ocr.fullText.take(500)}")

                // Log all blocks with bounds for debugging
                for (block in ocr.blocks) {
                    if (block.bounds != null) {
                        Log.i(TAG, "OCR block: '${block.text.take(60)}' at Y=${block.bounds!!.top}-${block.bounds!!.bottom}")
                    }
                }

                // The pickup field is at ~Y=548, destination field at ~Y=637.
                // Search RESULTS start below ~Y=700.
                // We must ONLY tap in the results area (Y >= 700).

                // Strategy 1: Find the distance line (e.g. "6.9 mi 72 Bauddhaloka Mawatha")
                // This is unique to search results — has the "X.X mi" distance prefix.
                val distanceBlock = ocr.blocks.find { block ->
                    val top = block.bounds?.top ?: 0
                    top >= 700 && top <= 1200 &&
                    block.text.contains(Regex("\\d+\\.?\\d*\\s*mi\\b"))
                }
                if (distanceBlock?.bounds != null) {
                    val b = distanceBlock.bounds!!
                    Log.i(TAG, "Tapping distance/address line via OCR: '${distanceBlock.text}' at (540, ${b.centerY()})")
                    ActionExecutor.tapAtCoordinates(service, 540f, b.centerY().toFloat())
                    return@AutomationStep StepResult.Success
                }

                // Strategy 2: Find destination name in the results area (Y >= 700)
                for (word in destWords) {
                    val resultBlocks = ScreenReader.findTextBlocks(ocr, word)
                        .filter { it.bounds != null && it.bounds!!.top >= 700 && it.bounds!!.top <= 1200 }
                        .sortedBy { it.bounds!!.top }

                    if (resultBlocks.isNotEmpty()) {
                        val resultBlock = resultBlocks.first()
                        val b = resultBlock.bounds!!
                        Log.i(TAG, "Tapping result for '$word': '${resultBlock.text}' at (540, ${b.centerY()})")
                        ActionExecutor.tapAtCoordinates(service, 540f, b.centerY().toFloat())
                        return@AutomationStep StepResult.Success
                    }
                }
            }

            StepResult.Retry("No search results found")
        }
    )

    /**
     * Wait for ride options screen (shows Tuk, Moto, Zip with prices).
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

                val hasPrice = ocr.fullText.contains("LKR", ignoreCase = true) ||
                        ocr.fullText.contains("Rs", ignoreCase = true)
                val hasVehicle = ocr.fullText.contains("Tuk", ignoreCase = true) ||
                        ocr.fullText.contains("Moto", ignoreCase = true) ||
                        ocr.fullText.contains("Zip", ignoreCase = true)
                val hasChoose = ocr.fullText.contains("Choose", ignoreCase = true)

                if (hasPrice && hasVehicle) {
                    Log.i(TAG, "Ride options detected! hasPrice=$hasPrice hasVehicle=$hasVehicle hasChoose=$hasChoose")
                    return@AutomationStep StepResult.Success
                }

                Log.i(TAG, "Not ride options yet. hasPrice=$hasPrice hasVehicle=$hasVehicle hasChoose=$hasChoose")
            }

            StepResult.Retry("Ride options not visible yet")
        }
    )

    /**
     * Read price from the ride options screen.
     * Uber shows prices per ride type — match by proximity to ride type label.
     */
    private fun readPrice(uberRideType: String) = AutomationStep(
        name = "Read Uber price ($uberRideType)",
        waitCondition = { root ->
            root.packageName?.toString() == PACKAGE
        },
        timeoutMs = 15_000,
        maxRetries = 5,
        delayBeforeMs = 500,
        action = { root, _ ->
            val service = AutomataAccessibilityService.instance.value
                ?: return@AutomationStep StepResult.Failure("No accessibility service")

            // Use OCR with proximity matching to get the correct ride type's price.
            // Uber shows multiple ride types (Tuk, Moto, Zip, Minivan) with prices —
            // we need to match the price closest to our target ride type label.
            val ocr = ScreenReader.captureAndRead(service)
            if (ocr != null) {
                Log.i(TAG, "Price reading OCR: ${ocr.fullText.take(500)}")

                val pricePattern = Regex("""(?:LKR|[Rr]s\.?)\s*(\d[\d,.]*)""", RegexOption.IGNORE_CASE)
                val allPrices = mutableListOf<Pair<String, Rect?>>()

                for (block in ocr.blocks) {
                    // Fix common OCR misreads in price text before matching
                    val cleanedText = block.text
                        .replace("l", "1")  // lowercase L → 1
                        .replace("O", "0")  // uppercase O → 0 (only in numeric context)
                        .replace("I", "1")  // uppercase I → 1
                    val match = pricePattern.find(cleanedText)
                        ?: pricePattern.find(block.text) // Try original text too
                    if (match != null) {
                        val price = match.groupValues[1].replace(",", "")
                        allPrices.add(price to block.bounds)
                        Log.i(TAG, "Found price: LKR $price at ${block.bounds} (raw: '${block.text}')")
                    }
                }

                if (allPrices.isNotEmpty()) {
                    // Match price to ride type by proximity
                    val rideTypeBlocks = ScreenReader.findTextBlocks(ocr, uberRideType)
                    val price = if (rideTypeBlocks.isNotEmpty() && rideTypeBlocks.first().bounds != null && allPrices.size > 1) {
                        val rideBounds = rideTypeBlocks.first().bounds!!
                        Log.i(TAG, "Ride type '$uberRideType' at X=${rideBounds.centerX()}, Y=${rideBounds.centerY()}")
                        // Try X-distance first (horizontal layout), fall back to Y-distance (vertical)
                        allPrices.minByOrNull { (_, bounds) ->
                            if (bounds != null) {
                                // Use combined distance to handle both horizontal and vertical layouts
                                val dx = kotlin.math.abs(bounds.centerX() - rideBounds.centerX())
                                val dy = kotlin.math.abs(bounds.centerY() - rideBounds.centerY())
                                dx + dy
                            } else Int.MAX_VALUE
                        }?.first ?: allPrices.first().first
                    } else {
                        allPrices.first().first
                    }

                    Log.i(TAG, "Uber price for $uberRideType: LKR $price")
                    return@AutomationStep StepResult.SuccessWithData("uber_price", price)
                }

                Log.w(TAG, "No prices found in OCR")
            }

            StepResult.Retry("Could not extract Uber price")
        }
    )

    /**
     * Tap the ride type label (Moto, Tuk, Zip) to select it.
     */
    private fun selectRideType(uberRideType: String) = AutomationStep(
        name = "Select ride type: $uberRideType",
        waitCondition = { root ->
            root.packageName?.toString() == PACKAGE
        },
        timeoutMs = 10_000,
        delayAfterMs = 1500,
        maxRetries = 3,
        action = { root, _ ->
            val service = AutomataAccessibilityService.instance.value
                ?: return@AutomationStep StepResult.Failure("No accessibility service")

            // Try accessibility first
            val node = NodeFinder.findByText(root, uberRideType)
            if (node != null) {
                Log.i(TAG, "Tapping ride type '$uberRideType' via accessibility")
                if (ActionExecutor.click(node)) {
                    return@AutomationStep StepResult.Success
                }
            }

            // OCR fallback
            val ocr = ScreenReader.captureAndRead(service)
            if (ocr != null) {
                val blocks = ScreenReader.findTextBlocks(ocr, uberRideType)
                if (blocks.isNotEmpty() && blocks.first().bounds != null) {
                    val b = blocks.first().bounds!!
                    Log.i(TAG, "Tapping ride type '$uberRideType' via OCR at (${b.centerX()}, ${b.centerY()})")
                    ActionExecutor.tapAtCoordinates(service, b.centerX().toFloat(), b.centerY().toFloat())
                    return@AutomationStep StepResult.Success
                }
            }

            StepResult.Retry("Could not find ride type '$uberRideType'")
        }
    )

    /**
     * Tap the "Choose [type]" button (e.g. "Choose Moto") to confirm booking.
     */
    private fun tapChooseRide(uberRideType: String) = AutomationStep(
        name = "Tap Choose $uberRideType",
        waitCondition = { root ->
            root.packageName?.toString() == PACKAGE
        },
        timeoutMs = 10_000,
        delayAfterMs = 2000,
        maxRetries = 3,
        action = { root, _ ->
            val service = AutomataAccessibilityService.instance.value
                ?: return@AutomationStep StepResult.Failure("No accessibility service")

            val chooseText = "Choose $uberRideType"

            // Try accessibility first
            val node = NodeFinder.findByText(root, chooseText)
                ?: NodeFinder.findByText(root, "Choose a trip")
            if (node != null) {
                Log.i(TAG, "Tapping '$chooseText' via accessibility")
                if (ActionExecutor.click(node)) {
                    return@AutomationStep StepResult.Success
                }
            }

            // OCR fallback
            val ocr = ScreenReader.captureAndRead(service)
            if (ocr != null) {
                val blocks = ScreenReader.findTextBlocks(ocr, chooseText)
                    .ifEmpty { ScreenReader.findTextBlocks(ocr, "Choose") }
                if (blocks.isNotEmpty() && blocks.first().bounds != null) {
                    val b = blocks.first().bounds!!
                    Log.i(TAG, "Tapping '$chooseText' via OCR at (${b.centerX()}, ${b.centerY()})")
                    ActionExecutor.tapAtCoordinates(service, b.centerX().toFloat(), b.centerY().toFloat())
                    return@AutomationStep StepResult.Success
                }
            }

            StepResult.Retry("Could not find '$chooseText' button")
        }
    )
}
