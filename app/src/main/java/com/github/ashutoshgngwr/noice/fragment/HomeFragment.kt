package com.github.ashutoshgngwr.noice.fragment

import android.graphics.drawable.Animatable
import android.net.Uri
import android.os.Bundle
import android.support.v4.media.session.PlaybackStateCompat
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.navigation.NavController
import androidx.navigation.NavOptions
import androidx.navigation.findNavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.NavigationUI
import androidx.navigation.ui.setupWithNavController
import com.github.ashutoshgngwr.noice.BuildConfig
import com.github.ashutoshgngwr.noice.MediaPlayerService
import com.github.ashutoshgngwr.noice.NoiceApplication
import com.github.ashutoshgngwr.noice.R
import com.github.ashutoshgngwr.noice.databinding.HomeFragmentBinding
import com.github.ashutoshgngwr.noice.ext.launchInCustomTab
import com.github.ashutoshgngwr.noice.playback.PlaybackController
import com.github.ashutoshgngwr.noice.provider.AnalyticsProvider
import com.github.ashutoshgngwr.noice.provider.CastAPIProvider
import com.github.ashutoshgngwr.noice.repository.SettingsRepository
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode


class HomeFragment : Fragment() {

  private lateinit var binding: HomeFragmentBinding
  private lateinit var navController: NavController
  private lateinit var childNavController: NavController
  private lateinit var analyticsProvider: AnalyticsProvider
  private lateinit var castAPIProvider: CastAPIProvider

  private var playerManagerState = PlaybackStateCompat.STATE_STOPPED

  // Do not refresh user preference when reconstructing this fragment from a previously saved state.
  // For whatever reasons, it makes the bottom navigation view go out of sync.
  private val shouldDisplaySavedPresetsAsHomeScreen by lazy {
    SettingsRepository.newInstance(requireContext())
      .shouldDisplaySavedPresetsAsHomeScreen()
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setHasOptionsMenu(true)

    val app = NoiceApplication.of(requireContext())
    analyticsProvider = app.getAnalyticsProvider()
    castAPIProvider = app.getCastAPIProviderFactory().newInstance(requireContext())
    EventBus.getDefault().register(this)
  }

  override fun onDestroy() {
    EventBus.getDefault().unregister(this)
    castAPIProvider.clearSessionCallbacks()
    super.onDestroy()
  }

  override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
    binding = HomeFragmentBinding.inflate(inflater, container, false)
    return binding.root
  }

  override fun onViewCreated(view: View, state: Bundle?) {
    navController = view.findNavController()
    childNavController = childFragmentManager.findFragmentById(R.id.nav_host_fragment)
      .let { (it as NavHostFragment).navController }

    if (shouldDisplaySavedPresetsAsHomeScreen) {
      childNavController.navigate(
        R.id.saved_presets, null, NavOptions.Builder()
          .setPopUpTo(R.id.library, true)
          .build()
      )
    }

    binding.bottomNav.setupWithNavController(childNavController)
  }

  override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
    castAPIProvider.addMenuItem(menu, R.string.cast_media)
    menu.add(0, R.id.action_play_pause_toggle, 0, R.string.play_pause).also {
      it.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
      it.isVisible = PlaybackStateCompat.STATE_STOPPED != playerManagerState
      if (PlaybackStateCompat.STATE_PLAYING == playerManagerState) {
        it.setIcon(R.drawable.ic_action_play_to_pause)
      } else {
        it.setIcon(R.drawable.ic_action_pause_to_play)
      }

      (it.icon as Animatable).start()
      it.setOnMenuItemClickListener {
        if (PlaybackStateCompat.STATE_PLAYING == playerManagerState) {
          PlaybackController.pause(requireContext())
        } else {
          PlaybackController.resume(requireContext())
        }

        true
      }
    }

    inflater.inflate(R.menu.home_menu, menu)
    super.onCreateOptionsMenu(menu, inflater)
  }

  override fun onOptionsItemSelected(item: MenuItem): Boolean {
    if (NavigationUI.onNavDestinationSelected(item, childNavController)) {
      return true
    }

    if (NavigationUI.onNavDestinationSelected(item, navController)) {
      return true
    }

    when (item.itemId) {
      R.id.report_issue -> {
        var url = getString(R.string.app_issues_github_url)
        if (BuildConfig.IS_PLAY_STORE_BUILD) {
          url = getString(R.string.app_issues_form_url)
        }

        Uri.parse(url).launchInCustomTab(requireContext())
        analyticsProvider.logEvent("issue_tracker_open", bundleOf())
      }

      R.id.submit_feedback -> {
        Uri.parse(getString(R.string.feedback_form_url)).launchInCustomTab(requireContext())
        analyticsProvider.logEvent("feedback_form_open", bundleOf())
      }

      else -> return super.onOptionsItemSelected(item)
    }

    return true
  }

  @Subscribe(sticky = true, threadMode = ThreadMode.MAIN)
  fun onPlayerManagerUpdate(event: MediaPlayerService.PlaybackUpdateEvent) {
    if (playerManagerState == event.state) {
      return
    }

    playerManagerState = event.state
    activity?.invalidateOptionsMenu()
  }
}