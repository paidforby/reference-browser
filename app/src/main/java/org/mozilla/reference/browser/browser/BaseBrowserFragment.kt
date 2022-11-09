/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.browser

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.CallSuper
import androidx.compose.ui.platform.ComposeView
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceManager
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.browser.toolbar.BrowserToolbar
import mozilla.components.browser.toolbar.behavior.BrowserToolbarBehavior
import mozilla.components.compose.browser.toolbar.BrowserToolbar
import mozilla.components.concept.engine.EngineView
import mozilla.components.feature.app.links.AppLinksFeature
import mozilla.components.feature.downloads.DownloadsFeature
import mozilla.components.feature.downloads.manager.FetchDownloadManager
import mozilla.components.feature.downloads.share.ShareDownloadFeature
import mozilla.components.feature.findinpage.view.FindInPageBar
import mozilla.components.feature.findinpage.view.FindInPageView
import mozilla.components.feature.prompts.PromptFeature
import mozilla.components.feature.session.FullScreenFeature
import mozilla.components.feature.session.SessionFeature
import mozilla.components.feature.session.SwipeRefreshFeature
import mozilla.components.feature.session.behavior.EngineViewBrowserToolbarBehavior
import mozilla.components.feature.sitepermissions.SitePermissionsFeature
import mozilla.components.feature.tabs.WindowFeature
import mozilla.components.feature.webauthn.WebAuthnFeature
import mozilla.components.support.base.feature.ActivityResultHandler
import mozilla.components.support.base.feature.PermissionsFeature
import mozilla.components.support.base.feature.UserInteractionHandler
import mozilla.components.support.base.feature.ViewBoundFeatureWrapper
import mozilla.components.support.base.log.logger.Logger
import mozilla.components.support.ktx.android.view.enterToImmersiveMode
import mozilla.components.support.ktx.android.view.exitImmersiveMode
import org.mozilla.reference.browser.AppPermissionCodes.REQUEST_CODE_APP_PERMISSIONS
import org.mozilla.reference.browser.AppPermissionCodes.REQUEST_CODE_DOWNLOAD_PERMISSIONS
import org.mozilla.reference.browser.AppPermissionCodes.REQUEST_CODE_PROMPT_PERMISSIONS
import org.mozilla.reference.browser.BuildConfig
import org.mozilla.reference.browser.databinding.FragmentBrowserBinding
import org.mozilla.reference.browser.R
import org.mozilla.reference.browser.downloads.DownloadService
import org.mozilla.reference.browser.ext.enableDynamicBehavior
import org.mozilla.reference.browser.ext.getPreferenceKey
import org.mozilla.reference.browser.ext.requireComponents
import org.mozilla.reference.browser.pip.PictureInPictureIntegration
import mozilla.components.browser.toolbar.behavior.ToolbarPosition as MozacToolbarBehaviorToolbarPosition
import mozilla.components.feature.session.behavior.ToolbarPosition as MozacEngineBehaviorToolbarPosition

/**
 * Base fragment extended by [BrowserFragment] and [ExternalAppBrowserFragment].
 * This class only contains shared code focused on the main browsing content.
 * UI code specific to the app or to custom tabs can be found in the subclasses.
 */
@Suppress("TooManyFunctions")
abstract class BaseBrowserFragment : Fragment(), UserInteractionHandler, ActivityResultHandler {
    private var _binding: FragmentBrowserBinding? = null
    private val binding get() = _binding!!

    private val sessionFeature = ViewBoundFeatureWrapper<SessionFeature>()
    private val toolbarIntegration = ViewBoundFeatureWrapper<ToolbarIntegration>()
    private val contextMenuIntegration = ViewBoundFeatureWrapper<ContextMenuIntegration>()
    private val downloadsFeature = ViewBoundFeatureWrapper<DownloadsFeature>()
    private val shareDownloadsFeature = ViewBoundFeatureWrapper<ShareDownloadFeature>()
    private val appLinksFeature = ViewBoundFeatureWrapper<AppLinksFeature>()
    private val promptsFeature = ViewBoundFeatureWrapper<PromptFeature>()
    private val fullScreenFeature = ViewBoundFeatureWrapper<FullScreenFeature>()
    private val findInPageIntegration = ViewBoundFeatureWrapper<FindInPageIntegration>()
    private val sitePermissionFeature = ViewBoundFeatureWrapper<SitePermissionsFeature>()
    private val pictureInPictureIntegration = ViewBoundFeatureWrapper<PictureInPictureIntegration>()
    private val swipeRefreshFeature = ViewBoundFeatureWrapper<SwipeRefreshFeature>()
    private val windowFeature = ViewBoundFeatureWrapper<WindowFeature>()
    private val webAuthnFeature = ViewBoundFeatureWrapper<WebAuthnFeature>()

    private val backButtonHandler: List<ViewBoundFeatureWrapper<*>> = listOf(
        fullScreenFeature,
        findInPageIntegration,
        toolbarIntegration,
        sessionFeature
    )

    private val activityResultHandler: List<ViewBoundFeatureWrapper<*>> = listOf(
        webAuthnFeature,
        promptsFeature
    )

    protected val sessionId: String?
        get() = arguments?.getString(SESSION_ID)

    protected var webAppToolbarShouldBeVisible = true

    final override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentBrowserBinding.inflate(inflater, container, false)
        return binding.root
    }

    abstract val shouldUseComposeUI: Boolean

    @CallSuper
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())

        sessionFeature.set(
            feature = SessionFeature(
                requireComponents.core.store,
                requireComponents.useCases.sessionUseCases.goBack,
                binding.engineView,
                sessionId
            ),
            owner = this,
            view = view
        )

        toolbarIntegration.set(
            feature = ToolbarIntegration(
                requireContext(),
                binding.toolbar,
                requireComponents.core.historyStorage,
                requireComponents.core.store,
                requireComponents.useCases.sessionUseCases,
                requireComponents.useCases.tabsUseCases,
                requireComponents.useCases.webAppUseCases,
                sessionId
            ),
            owner = this,
            view = view
        )

        contextMenuIntegration.set(
            feature = ContextMenuIntegration(
                requireContext(),
                parentFragmentManager,
                requireComponents.core.store,
                requireComponents.useCases.tabsUseCases,
                requireComponents.useCases.contextMenuUseCases,
                binding.engineView,
                view,
                sessionId
            ),
            owner = this,
            view = view
        )
        shareDownloadsFeature.set(
            ShareDownloadFeature(
                context = requireContext().applicationContext,
                httpClient = requireComponents.core.client,
                store = requireComponents.core.store,
                tabId = sessionId
            ),
            owner = this,
            view = view
        )

        downloadsFeature.set(
            feature = DownloadsFeature(
                requireContext(),
                store = requireComponents.core.store,
                useCases = requireComponents.useCases.downloadsUseCases,
                fragmentManager = childFragmentManager,
                downloadManager = FetchDownloadManager(
                    requireContext().applicationContext,
                    requireComponents.core.store,
                    DownloadService::class
                ),
                onNeedToRequestPermissions = { permissions ->
                    // The Fragment class wants us to use registerForActivityResult
                    @Suppress("DEPRECATION")
                    requestPermissions(permissions, REQUEST_CODE_DOWNLOAD_PERMISSIONS)
                }
            ),
            owner = this,
            view = view
        )

        appLinksFeature.set(
            feature = AppLinksFeature(
                requireContext(),
                store = requireComponents.core.store,
                sessionId = sessionId,
                fragmentManager = parentFragmentManager,
                launchInApp = {
                    prefs.getBoolean(requireContext().getPreferenceKey(R.string.pref_key_launch_external_app), false)
                }
            ),
            owner = this,
            view = view
        )

        promptsFeature.set(
            feature = PromptFeature(
                fragment = this,
                store = requireComponents.core.store,
                customTabId = sessionId,
                fragmentManager = parentFragmentManager,
                onNeedToRequestPermissions = { permissions ->
                    // The Fragment class wants us to use registerForActivityResult
                    @Suppress("DEPRECATION")
                    requestPermissions(permissions, REQUEST_CODE_PROMPT_PERMISSIONS)
                }
            ),
            owner = this,
            view = view
        )

        windowFeature.set(
            feature = WindowFeature(requireComponents.core.store, requireComponents.useCases.tabsUseCases),
            owner = this,
            view = view
        )

        fullScreenFeature.set(
            feature = FullScreenFeature(
                store = requireComponents.core.store,
                sessionUseCases = requireComponents.useCases.sessionUseCases,
                tabId = sessionId,
                viewportFitChanged = ::viewportFitChanged,
                fullScreenChanged = ::fullScreenChanged
            ),
            owner = this,
            view = view
        )

        findInPageIntegration.set(
            feature = FindInPageIntegration(
                requireComponents.core.store,
                sessionId,
                binding.findInPageBar as FindInPageView,
                binding.engineView
            ),
            owner = this,
            view = view
        )

        sitePermissionFeature.set(
            feature = SitePermissionsFeature(
                context = requireContext(),
                fragmentManager = parentFragmentManager,
                sessionId = sessionId,
                storage = requireComponents.core.geckoSitePermissionsStorage,
                onNeedToRequestPermissions = { permissions ->
                    // The Fragment class wants us to use registerForActivityResult
                    @Suppress("DEPRECATION")
                    requestPermissions(permissions, REQUEST_CODE_APP_PERMISSIONS)
                },
                onShouldShowRequestPermissionRationale = { shouldShowRequestPermissionRationale(it) },
                store = requireComponents.core.store
            ),
            owner = this,
            view = view
        )

        pictureInPictureIntegration.set(
            feature = PictureInPictureIntegration(
                requireComponents.core.store,
                requireActivity(),
                sessionId
            ),
            owner = this,
            view = view
        )

        swipeRefreshFeature.set(
            feature = SwipeRefreshFeature(
                requireComponents.core.store,
                requireComponents.useCases.sessionUseCases.reload,
                binding.swipeRefresh
            ),
            owner = this,
            view = view
        )

        binding.toolbar.enableDynamicBehavior(
            requireContext(),
            binding.swipeRefresh,
            binding.engineView,
            prefs.getBoolean(requireContext().getPreferenceKey(R.string.pref_key_toolbar_position), false)
        )

        if (BuildConfig.MOZILLA_OFFICIAL) {
            webAuthnFeature.set(
                feature = WebAuthnFeature(
                    requireComponents.core.engine,
                    requireActivity()
                ),
                owner = this,
                view = view
            )
        }

        val composeView = view.findViewById<ComposeView>(R.id.compose_view)
        if (shouldUseComposeUI) {
            composeView.visibility = View.VISIBLE
            composeView.setContent { BrowserToolbar() }

            val params = binding.swipeRefresh.layoutParams as CoordinatorLayout.LayoutParams
            params.topMargin = resources.getDimensionPixelSize(R.dimen.browser_toolbar_height)
            binding.swipeRefresh.layoutParams = params
        }
    }

    private fun fullScreenChanged(enabled: Boolean) {
        if (enabled) {
            activity?.enterToImmersiveMode()
            binding.toolbar.visibility = View.GONE
            binding.engineView.setDynamicToolbarMaxHeight(0)
        } else {
            activity?.exitImmersiveMode()
            binding.toolbar.visibility = View.VISIBLE
            binding.engineView.setDynamicToolbarMaxHeight(resources.getDimensionPixelSize(R.dimen.browser_toolbar_height))
        }
    }

    private fun viewportFitChanged(viewportFit: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            requireActivity().window.attributes.layoutInDisplayCutoutMode = viewportFit
        }
    }

    @CallSuper
    override fun onBackPressed(): Boolean {
        return backButtonHandler.any { it.onBackPressed() }
    }

    final override fun onHomePressed(): Boolean {
        return pictureInPictureIntegration.get()?.onHomePressed() ?: false
    }

    final override fun onPictureInPictureModeChanged(enabled: Boolean) {
        val session = requireComponents.core.store.state.selectedTab
        val fullScreenMode = session?.content?.fullScreen ?: false
        // If we're exiting PIP mode and we're in fullscreen mode, then we should exit fullscreen mode as well.
        if (!enabled && fullScreenMode) {
            onBackPressed()
            fullScreenChanged(false)
        }
    }

    final override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        val feature: PermissionsFeature? = when (requestCode) {
            REQUEST_CODE_DOWNLOAD_PERMISSIONS -> downloadsFeature.get()
            REQUEST_CODE_PROMPT_PERMISSIONS -> promptsFeature.get()
            REQUEST_CODE_APP_PERMISSIONS -> sitePermissionFeature.get()
            else -> null
        }
        feature?.onPermissionsResult(permissions, grantResults)
    }

    companion object {
        private const val SESSION_ID = "session_id"

        @JvmStatic
        protected fun Bundle.putSessionId(sessionId: String?) {
            putString(SESSION_ID, sessionId)
        }
    }

    override fun onActivityResult(requestCode: Int, data: Intent?, resultCode: Int): Boolean {
        Logger.info(
            "Fragment onActivityResult received with " +
                "requestCode: $requestCode, resultCode: $resultCode, data: $data"
        )

        return activityResultHandler.any { it.onActivityResult(requestCode, data, resultCode) }
    }
}
