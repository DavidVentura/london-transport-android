package dev.davidv.londontransport.santander

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class TutorialScreenAction(val screen: TutorialScreen, val actions: Map<String, PolarisAction>)

@Serializable
internal data class TutorialRetryAction(@SerialName("retry_reason") val reason: TutorialRetryReason)

@Serializable
internal data class TutorialScreen(
  val id: String, val content: TutorialNode,
  @SerialName("dismiss_actions") val dismiss: List<TutorialNavigationAction> = emptyList(),
  @SerialName("view_render_actions") val onView: List<TutorialNavigationAction> = emptyList(),
  @SerialName("validations_rulesets") val validations: List<EmptyMessage> = emptyList(),
)

@Serializable
internal data class TutorialNode(
  @SerialName("box_layout") val box: TutorialLayout? = null,
  @SerialName("v_stack_layout") val stack: TutorialLayout? = null,
  @SerialName("scroll_layout") val scroll: TutorialLayout? = null,
  val carousel: TutorialCarousel? = null, val label: TutorialLabel? = null,
  val image: EmptyMessage? = null, val spacer: EmptyMessage? = null,
)

@Serializable internal data class TutorialLayout(val children: List<TutorialChild>)
@Serializable internal data class TutorialChild(val node: TutorialNode)
@Serializable internal data class TutorialLabel(@SerialName("plain_text") val text: String)
@Serializable internal data class TutorialCarousel(
  val pages: List<TutorialCarouselPage>,
  @SerialName("navigation_style") val navigation: TutorialNavigation,
)
@Serializable internal data class TutorialCarouselPage(
  val content: TutorialNode,
  @SerialName("on_view_actions") val onView: List<TutorialNavigationAction> = emptyList(),
)
@Serializable internal data class TutorialNavigation(@SerialName("buttons_with_checkmark") val buttons: TutorialNavigationButtons)
@Serializable internal data class TutorialNavigationButtons(val actions: List<TutorialNavigationAction>)
@Serializable internal data class TutorialNavigationAction(
  val id: String = "",
  @SerialName("lbs_bff_panel_action") val panel: EmptyMessage? = null,
  @SerialName("common_action") val common: TutorialCommonAction? = null,
  val dismiss: EmptyMessage? = null,
)
@Serializable internal data class TutorialCommonAction(@SerialName("track_analytics") val analytics: EmptyMessage? = null)

internal fun parseRideTutorial(action: TutorialScreenAction): MutationOutcome {
  val screen = action.screen
  if (screen.id != "tbs_tutorial_screen" || screen.dismiss.isNotEmpty() || screen.onView.isNotEmpty() || screen.validations.isNotEmpty()) {
    return MutationOutcome.Unavailable("Unsupported pre-ride screen returned by Santander Cycles")
  }
  val root = screen.content
  if (listOfNotNull(root.box, root.stack, root.scroll, root.carousel, root.label, root.image, root.spacer).size != 1) {
    return MutationOutcome.Unavailable("Invalid pre-ride tutorial layout")
  }
  val carouselNode = root.box?.children?.singleOrNull()?.node
    ?: return MutationOutcome.Unavailable("Unsupported pre-ride tutorial layout")
  if (listOfNotNull(carouselNode.box, carouselNode.stack, carouselNode.scroll, carouselNode.carousel, carouselNode.label, carouselNode.image, carouselNode.spacer).size != 1) {
    return MutationOutcome.Unavailable("Invalid pre-ride tutorial carousel")
  }
  val carousel = carouselNode.carousel ?: return MutationOutcome.Unavailable("Missing pre-ride tutorial carousel")
  val navigation = carousel.navigation.buttons.actions
  if (navigation.any { entry ->
      listOfNotNull(entry.panel, entry.common, entry.dismiss).size != 1 ||
        (entry.common != null && entry.common.analytics == null)
    }) return MutationOutcome.Unavailable("Unsupported pre-ride tutorial navigation")
  val selected = navigation.filter { it.panel != null }.singleOrNull()
    ?: return MutationOutcome.Unavailable("Missing pre-ride tutorial confirmation")
  val continuation = action.actions[selected.id]
    ?: return MutationOutcome.Unavailable("Missing pre-ride tutorial continuation")
  if (listOfNotNull(continuation.alert, continuation.getPass, continuation.list, continuation.track, continuation.fullScreen, continuation.retry).size != 1 ||
    continuation.retry?.reason != TutorialRetryReason("INTERSTITIAL_POLARIS_PRE_RIDE_TUTORIAL", "frt-completed")) {
    return MutationOutcome.Unavailable("Unsupported pre-ride tutorial continuation")
  }
  if (carousel.pages.isEmpty()) return MutationOutcome.Unavailable("Pre-ride tutorial has no pages")
  val pages = carousel.pages.map { page ->
    if (page.onView.isNotEmpty()) return MutationOutcome.Unavailable("Unsupported pre-ride tutorial page action")
    val labels = tutorialLabels(page.content) ?: return MutationOutcome.Unavailable("Unsupported pre-ride tutorial content")
    if (labels.size < 2 || labels.any(String::isBlank)) return MutationOutcome.Unavailable("Incomplete pre-ride tutorial content")
    TutorialPage(labels.first(), labels.drop(1).joinToString("\n\n"))
  }
  return MutationOutcome.Tutorial(RideTutorial(pages))
}

private fun tutorialLabels(node: TutorialNode): List<String>? {
  if (listOfNotNull(node.box, node.stack, node.scroll, node.carousel, node.label, node.image, node.spacer).size != 1) return null
  val layout = node.box ?: node.stack ?: node.scroll
  if (layout != null) {
    return layout.children.flatMap { tutorialLabels(it.node) ?: return null }
  }
  if (node.label != null) return listOf(node.label.text)
  if (node.image != null || node.spacer != null) return emptyList()
  return null
}
