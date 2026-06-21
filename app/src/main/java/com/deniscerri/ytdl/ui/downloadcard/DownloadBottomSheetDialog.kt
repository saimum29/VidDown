package com.deniscerri.ytdl.ui.downloadcard

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Patterns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.edit
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.deniscerri.ytdl.R
import com.deniscerri.ytdl.database.enums.DownloadType
import com.deniscerri.ytdl.database.models.DownloadItem
import com.deniscerri.ytdl.database.models.Format
import com.deniscerri.ytdl.database.models.FormatRecyclerView
import com.deniscerri.ytdl.database.models.ResultItem
import com.deniscerri.ytdl.database.repository.DownloadRepository
import com.deniscerri.ytdl.database.viewmodel.CommandTemplateViewModel
import com.deniscerri.ytdl.database.viewmodel.DownloadCardViewModel
import com.deniscerri.ytdl.database.viewmodel.DownloadViewModel
import com.deniscerri.ytdl.database.viewmodel.HistoryViewModel
import com.deniscerri.ytdl.database.viewmodel.ResultViewModel
import com.deniscerri.ytdl.receiver.ShareActivity
import com.deniscerri.ytdl.ui.BaseActivity
import com.deniscerri.ytdl.ui.adapter.FormatAdapter
import com.deniscerri.ytdl.ui.more.cookies.WebViewActivity
import com.deniscerri.ytdl.util.Extensions
import com.deniscerri.ytdl.util.UiUtil
import com.facebook.shimmer.ShimmerFrameLayout
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.elevation.SurfaceColors
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL


class DownloadBottomSheetDialog : BottomSheetDialogFragment() {
    private lateinit var tabLayout: TabLayout
    private lateinit var viewPager2: ViewPager2
    private lateinit var fragmentAdapter : DownloadFragmentAdapter
    private lateinit var downloadViewModel: DownloadViewModel
    private lateinit var historyViewModel: HistoryViewModel
    private lateinit var resultViewModel: ResultViewModel
    private lateinit var downloadCardViewModel: DownloadCardViewModel
    private lateinit var behavior: BottomSheetBehavior<View>
    private lateinit var commandTemplateViewModel : CommandTemplateViewModel
    private lateinit var sharedPreferences : SharedPreferences
    private lateinit var updateItem : Button
    private lateinit var view: View
    private lateinit var shimmerLoading :ShimmerFrameLayout
    private lateinit var title : View
    private lateinit var shimmerLoadingSubtitle : ShimmerFrameLayout
    private lateinit var subtitle : View
    private lateinit var parentActivity: BaseActivity
    private lateinit var thumbnailView: ImageView
    private lateinit var qualityRecyclerView: RecyclerView
    private lateinit var qualityAdapter: FormatAdapter
    private lateinit var rememberQualitySwitch: com.google.android.material.materialswitch.MaterialSwitch
    private lateinit var advancedContainer: View
    private var advancedExpanded = false
    private var hideThumb = false


    private lateinit var result: ResultItem
    private lateinit var type: DownloadType
    private var ignoreDuplicates: Boolean = false
    private var disableUpdateData : Boolean = false
    private var currentDownloadItem: DownloadItem? = null
    private var incognito: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        downloadViewModel = ViewModelProvider(requireActivity())[DownloadViewModel::class.java]
        historyViewModel = ViewModelProvider(requireActivity())[HistoryViewModel::class.java]
        resultViewModel = ViewModelProvider(requireActivity())[ResultViewModel::class.java]
        commandTemplateViewModel = ViewModelProvider(requireActivity())[CommandTemplateViewModel::class.java]
        downloadCardViewModel = ViewModelProvider(requireActivity())[DownloadCardViewModel::class.java]
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(requireContext())

        val res = downloadCardViewModel.resultItem
        val dwl = downloadCardViewModel.downloadItem

        type = arguments?.getSerializable("type") as DownloadType
        disableUpdateData = arguments?.getBoolean("disableUpdateData") == true
        ignoreDuplicates = arguments?.getBoolean("ignore_duplicates") == true

        if (res == null){
            dismiss()
            return
        }
        result = res
        currentDownloadItem = dwl
        incognito = currentDownloadItem?.incognito ?: sharedPreferences.getBoolean("incognito", false)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val downloadItem = getDownloadItem()
        downloadCardViewModel.setResultItem(result)
        downloadCardViewModel.setDownloadItem(downloadItem)
        arguments?.putSerializable("type", downloadItem.type)
    }

    @SuppressLint("RestrictedApi", "InflateParams")
    override fun setupDialog(dialog: Dialog, style: Int) {
        super.setupDialog(dialog, style)
        view = LayoutInflater.from(context).inflate(R.layout.download_bottom_sheet, null)
        dialog.setContentView(view)
        dialog.window?.navigationBarColor = SurfaceColors.SURFACE_1.getColor(requireActivity())
        parentActivity = activity as BaseActivity

        dialog.setOnShowListener {
            behavior = BottomSheetBehavior.from(view.parent as View)
            val displayMetrics = DisplayMetrics()
            requireActivity().windowManager.defaultDisplay.getMetrics(displayMetrics)
            if(resources.getBoolean(R.bool.isTablet) || resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE){
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.peekHeight = displayMetrics.heightPixels
            }
        }

        tabLayout = view.findViewById(R.id.download_tablayout)
        viewPager2 = view.findViewById(R.id.advanced_viewpager)
        updateItem = view.findViewById(R.id.update_item)
        viewPager2.isUserInputEnabled = false

        // Thumbnail
        thumbnailView = view.findViewById(R.id.bottom_sheet_thumbnail)
        hideThumb = sharedPreferences.getBoolean("hide_thumbnails", false)
        if (result.thumb.isNotBlank()) {
            thumbnailView.visibility = View.VISIBLE
            with(Extensions) { thumbnailView.loadThumbnail(hideThumb, result.thumb) }
        }

        // Quality RecyclerView
        qualityRecyclerView = view.findViewById(R.id.quality_recyclerview)
        qualityRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        qualityAdapter = FormatAdapter(
            object : FormatAdapter.OnItemClickListener {
                override fun onItemSelect(item: Format, audioFormats: List<Format>?) {
                    runCatching {
                        val isVideo = tabLayout.selectedTabPosition == 0
                        if (isVideo) {
                            // Video tab (0) → fragment index 1
                            (fragmentAdapter.fragments[1] as DownloadVideoFragment).updateSelectedVideoFormat(item)
                            qualityAdapter.selectedVideoFormat = item
                        } else {
                            // Audio tab (1) → fragment index 0
                            (fragmentAdapter.fragments[0] as DownloadAudioFragment).updateSelectedAudioFormat(item.format_id)
                            qualityAdapter.selectedAudioFormats.clear()
                            qualityAdapter.selectedAudioFormats.add(item)
                        }
                        // Reflect the tap immediately as a checked item, instead of silently
                        // updating data with no visual confirmation.
                        qualityAdapter.notifyDataSetChanged()

                        if (rememberQualitySwitch.isChecked) {
                            sharedPreferences.edit(commit = true) {
                                if (isVideo) {
                                    putInt("remembered_video_quality_height", item.height ?: -1)
                                } else {
                                    putString("remembered_audio_quality_note", item.format_note)
                                }
                            }
                        }
                    }
                }
            }, requireActivity()
        )
        qualityRecyclerView.adapter = qualityAdapter
        qualityRecyclerView.isNestedScrollingEnabled = false

        // Remember my choice toggle
        rememberQualitySwitch = view.findViewById(R.id.remember_quality_choice_switch)
        rememberQualitySwitch.isChecked = sharedPreferences.getBoolean("remember_quality_choice", false)
        rememberQualitySwitch.setOnCheckedChangeListener { _, isChecked ->
            sharedPreferences.edit(commit = true) {
                putBoolean("remember_quality_choice", isChecked)
            }
        }

        // Advanced Options toggle
        advancedContainer = view.findViewById(R.id.advanced_options_container)
        val advancedBtn = view.findViewById<MaterialButton>(R.id.advanced_options_button)
        advancedBtn.setOnClickListener {
            advancedExpanded = !advancedExpanded
            advancedContainer.visibility = if (advancedExpanded) View.VISIBLE else View.GONE
            advancedBtn.setIconResource(
                if (advancedExpanded) R.drawable.baseline_expand_more_24
                else R.drawable.baseline_expand_less_24
            )
        }

        //loading shimmers
        shimmerLoading = view.findViewById(R.id.shimmer_loading_title)
        title = view.findViewById(R.id.bottom_sheet_title)
        shimmerLoadingSubtitle = view.findViewById(R.id.shimmer_loading_subtitle)
        subtitle = view.findViewById(R.id.bottom_sheet_subtitle)

        shimmerLoading.setOnClickListener {
            lifecycleScope.launch {
                resultViewModel.cancelUpdateItemData()
                (updateItem.parent as LinearLayout).visibility = View.VISIBLE
            }
        }


        (viewPager2.getChildAt(0) as? RecyclerView)?.apply {
            isNestedScrollingEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }


        //check if the item has formats and its audio-only
        val formats = result.formats
        var isAudioOnly = formats.isNotEmpty() && formats.none { !it.format_note.contains("audio") }
        if (isAudioOnly){
            // Tab 0 = Video → disable it if audio-only
            (tabLayout.getChildAt(0) as? ViewGroup)?.getChildAt(0)?.isClickable = false
            (tabLayout.getChildAt(0) as? ViewGroup)?.getChildAt(0)?.alpha = 0.3f
        }

        //remove outdated player url of 1hr so it can refetch it in the cut player
        if (result.creationTime > System.currentTimeMillis() - 3600000) result.urls = ""
        val fragmentManager = parentFragmentManager
        fragmentAdapter = DownloadFragmentAdapter(
            fragmentManager,
            lifecycle,
            result,
            currentDownloadItem,
            nonSpecific = result.url.endsWith(".txt"),
            isIncognito = incognito
        )

        viewPager2.adapter = fragmentAdapter
        viewPager2.isSaveFromParentEnabled = false

        view.post {
            when(type) {
                DownloadType.audio -> {
                    tabLayout.getTabAt(1)!!.select()
                    viewPager2.setCurrentItem(0, false)
                }
                DownloadType.video -> {
                    if (isAudioOnly){
                        tabLayout.getTabAt(1)!!.select()
                        viewPager2.setCurrentItem(0, false)
                        Toast.makeText(context, getString(R.string.audio_only_item), Toast.LENGTH_SHORT).show()
                    }else{
                        tabLayout.getTabAt(0)!!.select()
                        viewPager2.setCurrentItem(1, false)
                    }
                }
                else -> {
                    tabLayout.getTabAt(0)!!.select()
                    viewPager2.setCurrentItem(1, false)
                }
            }
            // Only build the quick quality list once the correct tab/type is actually
            // settled, otherwise it can briefly (or, if the tab was already on that
            // position, permanently) reflect the wrong type - e.g. showing only audio
            // formats right after fetching for a video download.
            updateQualityList()
        }

        sharedPreferences.edit(commit = true) {
            putString("last_used_download_type",
                type.toString())
        }


        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                // Tab 0 = Video (fragment index 1), Tab 1 = Audio (fragment index 0)
                if (tab!!.position == 0 && isAudioOnly){
                    tabLayout.selectTab(tabLayout.getTabAt(1))
                    Toast.makeText(context, getString(R.string.audio_only_item), Toast.LENGTH_SHORT).show()
                } else {
                    val fragmentIdx = if (tab.position == 0) 1 else 0
                    viewPager2.setCurrentItem(fragmentIdx, false)
                    updateQualityList()
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        viewPager2.registerOnPageChangeCallback(object: ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                // fragment 0=Audio, 1=Video → tab 1=Audio, 0=Video
                val tabIdx = if (position == 0) 1 else 0
                tabLayout.selectTab(tabLayout.getTabAt(tabIdx))
                runCatching {
                    sharedPreferences.edit(commit = true) {
                        putString("last_used_download_type",
                            listOf(DownloadType.audio, DownloadType.video)[position].toString())
                    }
                    fragmentAdapter.updateWhenSwitching(viewPager2.currentItem)
                }
            }
        })

        viewPager2.setPageTransformer(BackgroundToForegroundPageTransformer())

        val shownFields= sharedPreferences.getStringSet("modify_download_card", requireContext().resources.getStringArray(R.array.modify_download_card_values).toSet())!!.toList()

        val scheduleBtn = view.findViewById<MaterialButton>(R.id.bottomsheet_schedule_button)
        scheduleBtn.visibility = if(shownFields.contains("schedule")){
            View.VISIBLE
        }else{
            View.GONE
        }
        val download = view.findViewById<Button>(R.id.bottomsheet_download_button)


        scheduleBtn.setOnClickListener{
            UiUtil.showDatePicker(fragmentManager, sharedPreferences) {
                lifecycleScope.launch {
                    resultViewModel.cancelUpdateItemData()
                    resultViewModel.cancelUpdateFormatsItemData()
                }

                scheduleBtn.isEnabled = false
                download.isEnabled = false
                val item: DownloadItem = getDownloadItem()
                item.status = DownloadRepository.Status.Scheduled.toString()
                item.downloadStartTime = it.timeInMillis
                if (item.videoPreferences.alsoDownloadAsAudio){
                    val itemsToQueue = mutableListOf<DownloadItem>()
                    itemsToQueue.add(item)

                    getAlsoAudioDownloadItem(finished = { audioDownloadItem ->
                        audioDownloadItem.downloadStartTime = it.timeInMillis
                        audioDownloadItem.status = DownloadRepository.Status.Scheduled.toString()
                        itemsToQueue.add(audioDownloadItem)

                        lifecycleScope.launch {
                            val result = withContext(Dispatchers.IO){
                                downloadViewModel.queueDownloads(itemsToQueue, ignoreDuplicates)
                            }

                            if (result.message.isNotBlank()){
                                Toast.makeText(requireContext(), result.message, Toast.LENGTH_LONG).show()
                            }

                            withContext(Dispatchers.Main){
                                handleDuplicatesAndDismiss(result.duplicateDownloadIDs)
                            }
                        }
                    })
                }else{
                    lifecycleScope.launch {
                        val result = withContext(Dispatchers.IO){
                            downloadViewModel.queueDownloads(listOf(item), ignoreDuplicates)
                        }

                        if (result.message.isNotBlank()){
                            Toast.makeText(requireContext(), result.message, Toast.LENGTH_LONG).show()
                        }

                        withContext(Dispatchers.Main){
                            handleDuplicatesAndDismiss(result.duplicateDownloadIDs)
                        }
                    }
                }

            }
        }
        download!!.setOnClickListener {
            lifecycleScope.launch {
                resultViewModel.cancelUpdateItemData()
                resultViewModel.cancelUpdateFormatsItemData()
                scheduleBtn.isEnabled = false
                download.isEnabled = false
                val item: DownloadItem = getDownloadItem()
                if (item.videoPreferences.alsoDownloadAsAudio){
                    val itemsToQueue = mutableListOf<DownloadItem>()
                    itemsToQueue.add(item)

                    getAlsoAudioDownloadItem(finished = {
                        itemsToQueue.add(it)

                        lifecycleScope.launch {
                            val result = withContext(Dispatchers.IO) {
                                downloadViewModel.queueDownloads(itemsToQueue, ignoreDuplicates)
                            }
                            withContext(Dispatchers.Main){
                                handleDuplicatesAndDismiss(result.duplicateDownloadIDs)
                            }
                        }
                    })
                }else{
                    val result = withContext(Dispatchers.IO) {
                        downloadViewModel.queueDownloads(listOf(item), ignoreDuplicates)
                    }
                    handleDuplicatesAndDismiss(result.duplicateDownloadIDs)
                }
            }
        }

        download.setOnLongClickListener {
            val dd = MaterialAlertDialogBuilder(requireContext())
            dd.setTitle(getString(R.string.save_for_later))
            dd.setNegativeButton(getString(R.string.cancel)) { dialogInterface: DialogInterface, _: Int -> dialogInterface.cancel() }
            dd.setPositiveButton(getString(R.string.ok)) { _: DialogInterface?, _: Int ->
                lifecycleScope.launch(Dispatchers.IO){
                    downloadViewModel.putToSaved(getDownloadItem())
                    dismiss()
                }
            }
            dd.show()
            true
        }

        val link = view.findViewById<Button>(R.id.bottom_sheet_link)
        link.visibility = if(shownFields.contains("url")){
            View.VISIBLE
        }else{
            View.GONE
        }

        if (Patterns.WEB_URL.matcher(result.url).matches()){
            link.text = result.url
            link.setOnClickListener{
                UiUtil.openLinkIntent(requireContext(), result.url)
            }
            link.setOnLongClickListener{
                UiUtil.copyLinkToClipBoard(requireContext(), result.url)
                true
            }

            //if auto-update after the card is open is off
            if (result.title.isEmpty() && currentDownloadItem == null && sharedPreferences.getBoolean("quick_download", false)) {
                (updateItem.parent as LinearLayout).visibility = View.VISIBLE
                updateItem.setOnClickListener {
                    (updateItem.parent as LinearLayout).visibility = View.GONE
                    initUpdateData()
                }
            }else{
                (updateItem.parent as LinearLayout).visibility = View.GONE
            }

        }else{
            link.visibility = View.GONE
            (updateItem.parent as LinearLayout).visibility = View.GONE
        }

        val incognitoBtn = view.findViewById<Button>(R.id.bottomsheet_incognito)
        incognitoBtn.alpha = if (incognito) 1f else 0.3f
        incognitoBtn.setOnClickListener {
            if (incognito) {
                it.alpha = 0.3f
            }else{
                it.alpha = 1f
            }

            incognito = !incognito
            fragmentAdapter.isIncognito = incognito
            val onOff = if (incognito) getString(R.string.ok) else getString(R.string.disabled)
            Snackbar.make(incognitoBtn, "${getString(R.string.incognito)}: $onOff", Snackbar.LENGTH_SHORT).show()
        }


        //update in the background if there is no data
        if (!disableUpdateData) {
            if(result.title.isEmpty() && currentDownloadItem == null && !sharedPreferences.getBoolean("quick_download", false) && type != DownloadType.command){
                initUpdateData()
            }else {
                val usingGenericFormatsOrEmpty = result.formats.isEmpty() || result.formats.any { it.format_note.contains("viddowngeneric") }
                if (usingGenericFormatsOrEmpty && sharedPreferences.getBoolean("update_formats", true) && !sharedPreferences.getBoolean("quick_download", false)){
                    initUpdateFormats(result)
                }
            }
        }

        lifecycleScope.launch {
            resultViewModel.uiState.collectLatest { res ->
                if (res.errorMessage != null){
                    kotlin.runCatching {
                        UiUtil.handleNoResults(requireActivity(), res.errorMessage!!,
                            url = result.url,
                            continueAnyway =  true,
                            continued = {},
                            cookieFetch = {
                                val myIntent = Intent(requireContext(), WebViewActivity::class.java)
                                myIntent.putExtra("url", "https://${URL(result.url).host}")
                                cookiesFetchedResultLauncher.launch(myIntent)
                            },
                            closed = {
                                dismiss()
                            }
                        )
                    }

                    resultViewModel.uiState.update {it.copy(errorMessage  = null) }
                }
            }
        }

        lifecycleScope.launch {
            resultViewModel.updatingData.collectLatest {
                kotlin.runCatching {
                    if (it){
                        title.visibility = View.GONE
                        subtitle.visibility = View.GONE
                        shimmerLoading.visibility = View.VISIBLE
                        shimmerLoadingSubtitle.visibility = View.VISIBLE
                        shimmerLoading.startShimmer()
                        shimmerLoadingSubtitle.startShimmer()
                        (updateItem.parent as LinearLayout).visibility = View.GONE
                    }else{
                        title.visibility = View.VISIBLE
                        subtitle.visibility = View.VISIBLE
                        shimmerLoading.visibility = View.GONE
                        shimmerLoadingSubtitle.visibility = View.GONE
                        shimmerLoading.stopShimmer()
                        shimmerLoadingSubtitle.stopShimmer()
                    }
                }
            }
        }

        lifecycleScope.launch {
            resultViewModel.updatingFormats.collectLatest {
                kotlin.runCatching {
                    if (it){
                        delay(500)
                        runCatching {
                            (fragmentAdapter.fragments[0] as DownloadAudioFragment).apply {
                                view?.findViewById<LinearProgressIndicator>(R.id.format_loading_progress)?.apply {
                                    isVisible = true
                                    isClickable = true
                                    setOnClickListener {
                                        lifecycleScope.launch {
                                            resultViewModel.cancelUpdateFormatsItemData()
                                        }
                                    }
                                }
                            }
                        }
                        runCatching {
                            (fragmentAdapter.fragments[1] as DownloadVideoFragment).apply {
                                view?.findViewById<LinearProgressIndicator>(R.id.format_loading_progress)?.apply {
                                    isVisible = true
                                    isClickable = true
                                    setOnClickListener {
                                        lifecycleScope.launch {
                                            resultViewModel.cancelUpdateFormatsItemData()
                                        }
                                    }
                                }
                            }
                        }
                    }else{
                        runCatching {
                            (fragmentAdapter.fragments[0] as DownloadAudioFragment).apply {
                                view?.findViewById<LinearProgressIndicator>(R.id.format_loading_progress)?.apply {
                                    isVisible = false
                                    isClickable = false
                                }
                            }
                        }
                        runCatching {
                            (fragmentAdapter.fragments[1] as DownloadVideoFragment).apply {
                                view?.findViewById<LinearProgressIndicator>(R.id.format_loading_progress)?.apply {
                                    isVisible = false
                                    isClickable = false
                                }
                            }
                        }
                    }
                }
            }
        }

        lifecycleScope.launch {
            resultViewModel.updateResultData.collectLatest { result ->
                if (result == null) return@collectLatest
                kotlin.runCatching {
                    lifecycleScope.launch(Dispatchers.Main) {
                        if (result.size == 1 && result[0] != null) {
                            val res = result[0]!!
                            fragmentAdapter.setResultItem(res)

                            title.visibility = View.VISIBLE
                            subtitle.visibility = View.VISIBLE
                            shimmerLoading.visibility = View.GONE
                            shimmerLoadingSubtitle.visibility = View.GONE
                            shimmerLoading.stopShimmer()
                            shimmerLoadingSubtitle.stopShimmer()

                            val usingGenericFormatsOrEmpty = res.formats.isEmpty() || res.formats.any { it.format_note.contains("viddowngeneric") }
                            downloadCardViewModel.setResultItem(res)

                            // Refresh thumbnail if now available
                            if (res.thumb.isNotBlank()) {
                                thumbnailView.visibility = View.VISIBLE
                                with(Extensions) { thumbnailView.loadThumbnail(hideThumb, res.thumb) }
                            }
                            // Refresh quality list
                            updateQualityList()

                            if (usingGenericFormatsOrEmpty && sharedPreferences.getBoolean("update_formats", true)){
                                initUpdateFormats(res)
                            }

                        }else if (result.size > 1) {
                            //open multi download card instead
                            if (activity is ShareActivity){
                                findNavController().navigate(R.id.action_downloadBottomSheetDialog_to_selectPlaylistItemsDialog, bundleOf(
                                    Pair("resultIDs", result.map { it!!.id }.toLongArray()),
                                ))
                            }else{
                                dismiss()
                            }
                        }

                        resultViewModel.updateResultData.emit(null)
                    }

                }
            }
        }

        lifecycleScope.launch {
            launch{
                downloadViewModel.alreadyExistsUiState.collectLatest { res ->
                    if (res.isNotEmpty() && activity is ShareActivity){
                        withContext(Dispatchers.Main){
                            val bundle = bundleOf(
                                Pair("duplicates", ArrayList(res))
                            )
                            delay(500)
                            findNavController().navigate(R.id.action_downloadBottomSheetDialog_to_downloadsAlreadyExistDialog2, bundle)
                        }
                        downloadViewModel.alreadyExistsUiState.value = mutableListOf()
                    }
                }
            }
        }

        lifecycleScope.launch {
            resultViewModel.updateFormatsResultData.collectLatest { formats ->
                if (formats == null) return@collectLatest
                kotlin.runCatching {
                    isAudioOnly = formats.isNotEmpty() && formats.none { !it.format_note.contains("audio") }
                    if (isAudioOnly){
                        (tabLayout.getChildAt(0) as? ViewGroup)?.getChildAt(0)?.isClickable = false
                        (tabLayout.getChildAt(0) as? ViewGroup)?.getChildAt(0)?.alpha = 0.3f
                        Toast.makeText(context, getString(R.string.audio_only_item), Toast.LENGTH_SHORT).show()
                        tabLayout.getTabAt(1)!!.select()
                        viewPager2.setCurrentItem(0, false)
                    }

                    lifecycleScope.launch {
                        withContext(Dispatchers.Main){
                            runCatching {
                                val f1 = fragmentAdapter.fragments[0] as DownloadAudioFragment
                                val resultItem = downloadViewModel.createResultItemFromDownload(f1.downloadItem)
                                resultItem.formats = formats
                                fragmentAdapter.setResultItem(resultItem)
                                f1.view?.findViewById<LinearProgressIndicator>(R.id.format_loading_progress)?.visibility = View.GONE
                            }
                            runCatching {
                                val f1 = fragmentAdapter.fragments[1] as DownloadVideoFragment
                                val resultItem = downloadViewModel.createResultItemFromDownload(f1.downloadItem)
                                resultItem.formats = formats
                                fragmentAdapter.setResultItem(resultItem)
                                f1.view?.findViewById<LinearProgressIndicator>(R.id.format_loading_progress)?.visibility = View.GONE
                            }
                        }

                        if (formats.isNotEmpty()){
                            result.formats = formats
                            // refresh inline quality list (also applies remembered choice, if enabled)
                            updateQualityList()
                        }
                        resultViewModel.updateFormatsResultData.emit(null)
                    }
                }
            }
        }
    }

    private var cookiesFetchedResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            sharedPreferences.edit().putBoolean("use_cookies", true).apply()
            updateItem.isVisible = true
            initUpdateData()
        }
    }

    private fun getDownloadItem(selectedTabPosition: Int = tabLayout.selectedTabPosition) : DownloadItem {
        // tab 0 = Video = fragment[1], tab 1 = Audio = fragment[0]
        val fragmentIdx = if (selectedTabPosition == 0) 1 else 0
        return fragmentAdapter.getDownloadItem(fragmentIdx)
    }

    private fun getAlsoAudioDownloadItem(finished: (it: DownloadItem) -> Unit) {
        try {
            val ff = fragmentAdapter.fragments[0] as DownloadAudioFragment
            getDownloadItem(1).videoPreferences.audioFormatIDs.apply {
                if (this.isNotEmpty()) {
                    ff.updateSelectedAudioFormat(this.first())
                }
            }
            finished(ff.downloadItem)
        }catch (e: Exception){
            val fragmentLifecycleCallback = object:
                FragmentManager.FragmentLifecycleCallbacks() {

                override fun onFragmentStarted(fm: FragmentManager, f: Fragment) {
                    fragmentManager?.unregisterFragmentLifecycleCallbacks(this)
                    val ff = (f as DownloadAudioFragment)
                    ff.requireView().post {
                        ff.updateSelectedAudioFormat(getDownloadItem(1).videoPreferences.audioFormatIDs.first())
                        finished(ff.downloadItem)
                    }
                    super.onFragmentStarted(fm, f)
                }


            }

            fragmentManager?.registerFragmentLifecycleCallbacks(fragmentLifecycleCallback, true)
            viewPager2.setCurrentItem(0, true)
        }
    }

    private fun updateQualityList() {
        val isVideo = tabLayout.selectedTabPosition == 0  // tab 0 = Video
        val allFormats = result.formats
        val filtered = if (isVideo) {
            allFormats.filter { it.vcodec.isNotBlank() && it.vcodec != "none" }
                .sortedByDescending { it.filesize }
        } else {
            allFormats.filter { it.vcodec.isBlank() || it.vcodec == "none" }
                .sortedByDescending { it.filesize }
        }
        qualityAdapter.submitList(filtered.map { FormatRecyclerView(format = it) }.toMutableList())
        applyRememberedQualityIfEnabled(isVideo, filtered)
    }

    /**
     * If "Remember my choice" is on and a previous quality preference was saved, automatically
     * pick the closest match in this video's own format list (by resolution for video, by the
     * exact quality label for audio) instead of making the person reselect it every time.
     */
    private fun applyRememberedQualityIfEnabled(isVideo: Boolean, filtered: List<Format>) {
        if (!rememberQualitySwitch.isChecked || filtered.isEmpty()) return
        runCatching {
            if (isVideo) {
                val rememberedHeight = sharedPreferences.getInt("remembered_video_quality_height", -1)
                if (rememberedHeight <= 0) return
                val match = filtered.minByOrNull { kotlin.math.abs((it.height ?: 0) - rememberedHeight) } ?: return
                (fragmentAdapter.fragments[1] as DownloadVideoFragment).updateSelectedVideoFormat(match)
                qualityAdapter.selectedVideoFormat = match
            } else {
                val rememberedNote = sharedPreferences.getString("remembered_audio_quality_note", null)
                if (rememberedNote.isNullOrBlank()) return
                val match = filtered.find { it.format_note == rememberedNote } ?: return
                (fragmentAdapter.fragments[0] as DownloadAudioFragment).updateSelectedAudioFormat(match.format_id)
                qualityAdapter.selectedAudioFormats.clear()
                qualityAdapter.selectedAudioFormats.add(match)
            }
            qualityAdapter.notifyDataSetChanged()
        }
    }

    private fun initUpdateData() {
        kotlin.runCatching {
            if (result.url.isBlank()) {
                dismiss()
                return
            }
            if (resultViewModel.updatingData.value) return

            lifecycleScope.launch(Dispatchers.IO) {
                resultViewModel.updateItemData(result)
            }
        }
    }

    private fun initUpdateFormats(res: ResultItem){
        kotlin.runCatching {
            if (resultViewModel.updatingFormats.value) return
            CoroutineScope(SupervisorJob()).launch(Dispatchers.IO) {
                resultViewModel.updateFormatItemData(res)
            }
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        lifecycleScope.launch {
            resultViewModel.cancelUpdateItemData()
            resultViewModel.cancelUpdateFormatsItemData()
            super.onDismiss(dialog)
        }
    }

    private fun handleDuplicatesAndDismiss(res: List<DownloadViewModel.AlreadyExistsIDs>) {
        if (activity is ShareActivity && res.isNotEmpty()) {
            //let the lifecycle listener handle it
        }else{
            dismiss()
        }
    }
}
