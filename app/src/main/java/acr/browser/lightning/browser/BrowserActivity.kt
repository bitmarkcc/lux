package acr.browser.lightning.browser

import acr.browser.lightning.AppTheme
import acr.browser.lightning.R
import acr.browser.lightning.ThemableBrowserActivity
import acr.browser.lightning.animation.AnimationUtils
import acr.browser.lightning.browser.bookmark.BookmarkRecyclerViewAdapter
import acr.browser.lightning.browser.color.ColorAnimator
import acr.browser.lightning.browser.di.MainHandler
import acr.browser.lightning.browser.di.injector
import acr.browser.lightning.browser.image.ImageLoader
import acr.browser.lightning.browser.keys.KeyEventAdapter
import acr.browser.lightning.browser.menu.MenuItemAdapter
import acr.browser.lightning.browser.search.IntentExtractor
import acr.browser.lightning.browser.search.SearchListener
import acr.browser.lightning.browser.search.StyleRemovingTextWatcher
import acr.browser.lightning.browser.tab.BottomDrawerTabRecyclerViewAdapter
import acr.browser.lightning.browser.tab.DesktopTabRecyclerViewAdapter
import acr.browser.lightning.browser.tab.DrawerTabRecyclerViewAdapter
import acr.browser.lightning.browser.tab.TabPager
import acr.browser.lightning.browser.tab.TabViewHolder
import acr.browser.lightning.browser.tab.TabViewState
import acr.browser.lightning.browser.theme.ThemeProvider
import acr.browser.lightning.browser.ui.BookmarkConfiguration
import acr.browser.lightning.browser.ui.TabConfiguration
import acr.browser.lightning.browser.ui.UiConfiguration
import acr.browser.lightning.browser.view.ViewDelegate
import acr.browser.lightning.browser.view.delegates.BottomTabViewDelegate
import acr.browser.lightning.browser.view.delegates.DesktopTabViewDelegate
import acr.browser.lightning.browser.view.delegates.DrawerTabViewDelegate
import acr.browser.lightning.browser.view.targetUrl.LongPress
import acr.browser.lightning.constant.HTTP
import acr.browser.lightning.database.Bookmark
import acr.browser.lightning.database.HistoryEntry
import acr.browser.lightning.database.SearchSuggestion
import acr.browser.lightning.database.WebPage
import acr.browser.lightning.database.downloads.DownloadEntry
import acr.browser.lightning.databinding.BrowserActivityBottomBinding
import acr.browser.lightning.databinding.BrowserActivityDesktopBinding
import acr.browser.lightning.databinding.BrowserActivityDrawerBinding
import acr.browser.lightning.databinding.BrowserBottomTabsBinding
import acr.browser.lightning.dialog.BrowserDialog
import acr.browser.lightning.dialog.DialogItem
import acr.browser.lightning.dialog.LightningDialogBuilder
import acr.browser.lightning.extensions.color
import acr.browser.lightning.extensions.drawable
import acr.browser.lightning.extensions.resizeAndShow
import acr.browser.lightning.extensions.takeIfInstance
import acr.browser.lightning.extensions.tint
import acr.browser.lightning.preference.datastore.getUnsafe
import acr.browser.lightning.search.SuggestionsAdapter
import acr.browser.lightning.certimark.CertimarkChecker
import acr.browser.lightning.certimark.CertimarkResult
import acr.browser.lightning.certimark.CertimarkStatus
import acr.browser.lightning.ssl.createCertimarkDrawable
import acr.browser.lightning.ssl.createSslDrawableForState
import acr.browser.lightning.ssl.SslState
import acr.browser.lightning.utils.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.os.Handler
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.MenuRes
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.isVisible
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import javax.inject.Inject

/**
 * The base browser activity that governs the browsing experience for both default and incognito
 * browsers.
 */
abstract class BrowserActivity : ThemableBrowserActivity() {

    private lateinit var binding: ViewDelegate
    private lateinit var tabsAdapter: ListAdapter<TabViewState, TabViewHolder>
    private lateinit var bookmarksAdapter: BookmarkRecyclerViewAdapter
    private var activeRecyclerView: RecyclerView? = null

    private var menuItemShare: MenuItem? = null
    private var menuItemCopyLink: MenuItem? = null
    private var menuItemAddToHome: MenuItem? = null
    private var menuItemAddBookmark: MenuItem? = null

    private val defaultColor by lazy { color(R.color.primary_color) }
    private val backgroundDrawable by lazy { defaultColor.toDrawable() }

    private var customView: View? = null

    private var pendingScroll = -1

    private var certimarkJob: Job? = null
    private var certimarkResult: CertimarkResult? = null
    private var lastCheckedDomain: String? = null
    private var currentSslState: SslState = SslState.None
    private var currentPageUrl: String = ""
    private var certimarkChecker: CertimarkChecker? = null

    private fun getOrCreateCertimarkChecker(): CertimarkChecker {
        return certimarkChecker ?: run {
            val apiUrl = userPreferencesDataStore.certimarkApiUrl.getUnsafe()
            val keyPin = userPreferencesDataStore.certimarkApiKeyPin.getUnsafe().let {
                it.ifEmpty { null }
            }
            CertimarkChecker(
                okHttpClient = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .build(),
                apiUrl = apiUrl,
                apiKeyPin = keyPin
            ).also { certimarkChecker = it }
        }
    }

    @Suppress("ConvertLambdaToReference")
    private val launcher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { presenter.onFileChooserResult(it) }

    @Inject
    internal lateinit var imageLoader: ImageLoader

    @Inject
    internal lateinit var keyEventAdapter: KeyEventAdapter

    @Inject
    internal lateinit var menuItemAdapter: MenuItemAdapter

    @Inject
    internal lateinit var inputMethodManager: InputMethodManager

    @Inject
    internal lateinit var presenter: BrowserPresenter

    @Inject
    internal lateinit var tabPager: TabPager

    @Inject
    internal lateinit var intentExtractor: IntentExtractor

    @Inject
    internal lateinit var lightningDialogBuilder: LightningDialogBuilder

    @Inject
    internal lateinit var uiConfiguration: UiConfiguration

    @Inject
    internal lateinit var themeProvider: ThemeProvider

    @MainHandler
    @Inject
    internal lateinit var mainHandler: Handler

    /**
     * True if the activity is operating in incognito mode, false otherwise.
     */
    abstract fun isIncognito(): Boolean

    /**
     * Provide the menu used by the browser instance.
     */
    @MenuRes
    abstract fun menu(): Int

    /**
     * Provide the home icon used by the browser instance.
     */
    @DrawableRes
    abstract fun homeIcon(): Int

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = when (userPreferencesDataStore.tabConfiguration.getUnsafe()) {
            TabConfiguration.DESKTOP -> {
                val actualBinding = BrowserActivityDesktopBinding.inflate(LayoutInflater.from(this))
                DesktopTabViewDelegate(actualBinding)
            }

            TabConfiguration.DRAWER_SIDE -> {
                val actualBinding = BrowserActivityDrawerBinding.inflate(LayoutInflater.from(this))
                DrawerTabViewDelegate(actualBinding)
            }

            TabConfiguration.DRAWER_BOTTOM -> {
                val actualBinding = BrowserActivityBottomBinding.inflate(LayoutInflater.from(this))
                BottomTabViewDelegate(actualBinding)
            }
        }

        val bottomTabsBinding = if (binding.browserLayoutContainer != null) {
            BrowserBottomTabsBinding.inflate(layoutInflater)
        } else {
            null
        }

        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        injector.browser2ComponentBuilder()
            .activity(this)
            .browserFrame(binding.contentFrame)
            .bottomTabsLayout(bottomTabsBinding)
            .toolbarRoot(binding.uiLayout)
            .browserRoot(binding.browserLayoutContainer)
            .toolbar(binding.toolbarLayout)
            .initialIntent(intent.takeIf { savedInstanceState == null })
            .incognitoMode(isIncognito())
            .build()
            .inject(this)

        binding.drawerLayout.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {

            override fun onDrawerOpened(drawerView: View) {
                if (drawerView == binding.tabDrawer) {
                    presenter.onTabDrawerMoved(isOpen = true)
                } else if (drawerView == binding.bookmarkDrawer) {
                    presenter.onBookmarkDrawerMoved(isOpen = true)
                }
            }

            override fun onDrawerClosed(drawerView: View) {
                if (drawerView == binding.tabDrawer) {
                    presenter.onTabDrawerMoved(isOpen = false)
                } else if (drawerView == binding.bookmarkDrawer) {
                    presenter.onBookmarkDrawerMoved(isOpen = false)
                }
            }
        })

        binding.bookmarkDrawer.layoutParams =
            (binding.bookmarkDrawer.layoutParams as DrawerLayout.LayoutParams).apply {
                gravity = when (uiConfiguration.bookmarkConfiguration) {
                    BookmarkConfiguration.LEFT -> Gravity.START
                    BookmarkConfiguration.RIGHT -> Gravity.END
                }
            }

        binding.tabDrawer.layoutParams =
            (binding.tabDrawer.layoutParams as DrawerLayout.LayoutParams).apply {
                gravity = when (uiConfiguration.bookmarkConfiguration) {
                    BookmarkConfiguration.LEFT -> Gravity.END
                    BookmarkConfiguration.RIGHT -> Gravity.START
                }
            }

        binding.homeImageView.isVisible =
            uiConfiguration.tabConfiguration == TabConfiguration.DESKTOP || isIncognito()
        binding.homeImageView.setImageResource(homeIcon())
        binding.tabCountView.isVisible =
            uiConfiguration.tabConfiguration != TabConfiguration.DESKTOP && !isIncognito()

        if (uiConfiguration.tabConfiguration != TabConfiguration.DRAWER_SIDE) {
            binding.drawerLayout.setDrawerLockMode(
                DrawerLayout.LOCK_MODE_LOCKED_CLOSED,
                binding.tabDrawer
            )
        }

        if (uiConfiguration.tabConfiguration != TabConfiguration.DESKTOP) {
            if (binding.browserLayoutContainer == null) {
                tabsAdapter = DrawerTabRecyclerViewAdapter(
                    onClick = presenter::onTabClick,
                    onCloseClick = presenter::onTabClose,
                    onLongClick = presenter::onTabLongClick
                )
                binding.drawerTabsList.isVisible = true
                binding.drawerTabsList.adapter = tabsAdapter
                binding.drawerTabsList.layoutManager = LinearLayoutManager(this)
                binding.drawerTabsList.itemAnimator?.takeIfInstance<SimpleItemAnimator>()
                    ?.supportsChangeAnimations = false
                binding.desktopTabsList.isVisible = false
                activeRecyclerView = binding.desktopTabsList
            } else {
                tabsAdapter = BottomDrawerTabRecyclerViewAdapter(
                    themeProvider,
                    onClick = presenter::onTabClick,
                    onLongClick = presenter::onTabLongClick,
                    onCloseClick = presenter::onTabClose,
                    onBackClick = { presenter.onBackClick() },
                    onForwardClick = { presenter.onForwardClick() },
                    onHomeClick = { presenter.onHomeClick() }
                )
                bottomTabsBinding!!.bottomTabList.adapter = tabsAdapter
                bottomTabsBinding.bottomTabList.layoutManager =
                    LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
                bottomTabsBinding.bottomTabList.itemAnimator?.takeIfInstance<SimpleItemAnimator>()
                    ?.supportsChangeAnimations = false
                binding.drawerTabsList.isVisible = false
                binding.desktopTabsList.isVisible = false
                activeRecyclerView = bottomTabsBinding.bottomTabList
            }
        } else {
            tabsAdapter = DesktopTabRecyclerViewAdapter(
                context = this,
                onClick = presenter::onTabClick,
                onCloseClick = presenter::onTabClose,
                onLongClick = presenter::onTabLongClick
            )
            binding.desktopTabsList.isVisible = true
            binding.desktopTabsList.adapter = tabsAdapter
            binding.desktopTabsList.layoutManager =
                LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
            binding.desktopTabsList.itemAnimator?.takeIfInstance<SimpleItemAnimator>()
                ?.supportsChangeAnimations = false
            binding.drawerTabsList.isVisible = false
            activeRecyclerView = binding.desktopTabsList
        }

        bookmarksAdapter = BookmarkRecyclerViewAdapter(
            onClick = presenter::onBookmarkClick,
            onLongClick = presenter::onBookmarkLongClick,
            imageLoader = imageLoader
        )
        binding.bookmarkListView.adapter = bookmarksAdapter
        binding.bookmarkListView.layoutManager = LinearLayoutManager(this)

        presenter.onViewAttached(BrowserStateAdapter(this))

        val suggestionsAdapter = SuggestionsAdapter(this, isIncognito = isIncognito()).apply {
            onSuggestionInsertClick = {
                if (it is SearchSuggestion) {
                    binding.search.setText(it.title)
                    binding.search.setSelection(it.title.length)
                } else {
                    binding.search.setText(it.url)
                    binding.search.setSelection(it.url.length)
                }
            }
        }
        binding.search.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            binding.search.clearFocus()
            presenter.onSearchSuggestionClicked(suggestionsAdapter.getItem(position) as WebPage)
            inputMethodManager.hideSoftInputFromWindow(binding.root.windowToken, 0)
        }
        binding.search.setAdapter(suggestionsAdapter)
        val searchListener = SearchListener(
            onConfirm = { presenter.onSearch(binding.search.text.toString()) },
            inputMethodManager = inputMethodManager
        )
        binding.search.setOnEditorActionListener(searchListener)
        binding.search.setOnKeyListener(searchListener)
        binding.search.addTextChangedListener(StyleRemovingTextWatcher())
        binding.search.setOnFocusChangeListener { _, hasFocus ->
            presenter.onSearchFocusChanged(hasFocus)
            binding.search.selectAll()
        }

        binding.findPrevious.setOnClickListener { presenter.onFindPrevious() }
        binding.findNext.setOnClickListener { presenter.onFindNext() }
        binding.findQuit.setOnClickListener { presenter.onFindDismiss() }

        binding.homeButton.setOnClickListener { presenter.onTabCountViewClick() }
        binding.actionBack.setOnClickListener { presenter.onBackClick() }
        binding.actionForward.setOnClickListener { presenter.onForwardClick() }
        binding.actionHome.setOnClickListener { presenter.onHomeClick() }
        binding.newTabButton.setOnClickListener { presenter.onNewTabClick() }
        binding.newTabButton.setOnLongClickListener {
            presenter.onNewTabLongClick()
            true
        }
        binding.searchRefresh.setOnClickListener { presenter.onRefreshOrStopClick() }
        binding.actionAddBookmark.setOnClickListener { presenter.onStarClick() }
        binding.actionPageTools.setOnClickListener { presenter.onToolsClick() }
        binding.tabHeaderButton.setOnClickListener { presenter.onTabMenuClick() }
        binding.bookmarkBackButton.setOnClickListener { presenter.onBookmarkMenuClick() }
        binding.searchSslStatus.setOnClickListener { onShieldClick() }

        tabPager.longPressListener = presenter::onPageLongPress

        onBackPressedDispatcher.addCallback {
            presenter.onNavigateBack()
        }
    }

    private fun triggerCertimarkCheck(pageUrl: String) {
        val domain = try {
            URL(pageUrl).host
        } catch (_: Exception) {
            null
        } ?: return

        if (domain == lastCheckedDomain && certimarkResult != null) {
            binding.searchSslStatus.setImageDrawable(createCertimarkDrawable(certimarkResult!!.status))
            return
        }

        certimarkJob?.cancel()
        lastCheckedDomain = domain
        certimarkResult = null

        certimarkJob = CoroutineScope(Dispatchers.Main).launch {
            val result = withContext(Dispatchers.IO) {
                getOrCreateCertimarkChecker().check(domain)
            }
            certimarkResult = result
            if (lastCheckedDomain == domain) {
                binding.searchSslStatus.setImageDrawable(createCertimarkDrawable(result.status))
            }
        }
    }

    private fun onShieldClick() {
        val result = certimarkResult
        val sslState = currentSslState

        if (sslState is SslState.None || sslState == null) {
            // HTTP page
            AlertDialog.Builder(this)
                .setIcon(createSslDrawableForState(SslState.None))
                .setTitle(R.string.title_warning)
                .setMessage(getString(R.string.certimark_http_warning))
                .setPositiveButton(R.string.action_ok, null)
                .show()
            return
        }

        if (sslState is SslState.Invalid) {
            // SSL error — show original SSL dialog
            presenter.onSslIconClick()
            return
        }

        if (result == null) {
            // Check still in progress
            AlertDialog.Builder(this)
                .setTitle(lastCheckedDomain ?: "")
                .setMessage(getString(R.string.certimark_checking))
                .setPositiveButton(R.string.action_ok, null)
                .show()
            return
        }

        // Show Certimark result dialog
        val title = result.domain
        val descLine = result.description?.let { "$it\n\n" } ?: ""

        val message = descLine + when (result.status) {
            CertimarkStatus.MATCH_TOP -> {
                val label = if (result.keyMatch) getString(R.string.certimark_key_match) else getString(R.string.certimark_verified)
                val weight = result.matchedCert?.weight?.let { "\n${getString(R.string.certimark_weight)}: $it" } ?: ""
                "$label$weight"
            }
            CertimarkStatus.MATCH_OTHER -> {
                val label = if (result.keyMatch) getString(R.string.certimark_key_match_other) else getString(R.string.certimark_match_other)
                val weight = result.matchedCert?.weight?.let { "\n${getString(R.string.certimark_weight)}: $it" } ?: ""
                "$label$weight"
            }
            CertimarkStatus.NO_MATCH -> getString(R.string.certimark_no_match)
            CertimarkStatus.NOT_MARKED -> getString(R.string.certimark_not_marked)
            CertimarkStatus.ERROR -> getString(R.string.certimark_error, result.errorMessage ?: "")
            CertimarkStatus.HTTP_INSECURE -> getString(R.string.certimark_http_warning)
        }

        val fullHash = result.browserCertHash

        // Build custom view with copy icon next to hash
        val dp = resources.displayMetrics.density
        val pad = (20 * dp).toInt()

        val container = ScrollView(this).apply {
            setPadding(pad, (8 * dp).toInt(), pad, 0)
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        container.addView(layout)

        // Message text
        layout.addView(TextView(this).apply {
            text = message
            textSize = 16f
        })

        // Hash row with copy icon
        if (fullHash != null) {
            val hashRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, (12 * dp).toInt(), 0, 0)
            }
            val short = if (fullHash.length > 16) "${fullHash.take(8)}...${fullHash.takeLast(8)}" else fullHash
            hashRow.addView(TextView(this).apply {
                text = "${getString(R.string.certimark_browser_cert)}: $short"
                textSize = 14f
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            val copyBtn = ImageButton(this).apply {
                setImageResource(R.drawable.ic_copy)
                setBackgroundResource(android.R.color.transparent)
                contentDescription = "Copy hash"
                val btnSize = (32 * dp).toInt()
                layoutParams = LinearLayout.LayoutParams(btnSize, btnSize)
                setOnClickListener {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Certificate Hash", fullHash))
                    Toast.makeText(this@BrowserActivity, "Hash copied", Toast.LENGTH_SHORT).show()
                }
            }
            hashRow.addView(copyBtn)
            layout.addView(hashRow)
        }

        AlertDialog.Builder(this)
            .setIcon(createCertimarkDrawable(result.status))
            .setTitle(title)
            .setView(container)
            .setPositiveButton(R.string.action_ok, null)
            .show()
    }

    override fun onNewIntent(intent: Intent) {
        intentExtractor.extractUrlFromIntent(intent)?.let(presenter::onNewAction)
        super.onNewIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        presenter.onViewDetached()
    }

    override fun onResume() {
        super.onResume()
        // Invalidate checker so settings changes take effect
        certimarkChecker = null
    }

    override fun onPause() {
        super.onPause()
        presenter.onViewHidden()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(menu(), menu)
        menuItemShare = menu.findItem(R.id.action_share)
        menuItemCopyLink = menu.findItem(R.id.action_copy)
        menuItemAddToHome = menu.findItem(R.id.action_add_to_homescreen)
        menuItemAddBookmark = menu.findItem(R.id.action_add_bookmark)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return menuItemAdapter.adaptMenuItem(item)?.let(presenter::onMenuClick)?.let { true }
            ?: super.onOptionsItemSelected(item)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        return keyEventAdapter.adaptKeyEvent(event)?.let(presenter::onKeyComboClick)?.let { true }
            ?: super.onKeyUp(keyCode, event)
    }

    /**
     * @see BrowserContract.View.renderState
     */
    fun renderState(viewState: PartialBrowserViewState) {
        viewState.isBackEnabled?.let { binding.actionBack.isEnabled = it }
        viewState.isForwardEnabled?.let { binding.actionForward.isEnabled = it }
        viewState.displayUrl?.let(binding.search::setText)
        viewState.sslState?.let { sslState ->
            currentSslState = sslState
            binding.searchSslStatus.setImageDrawable(createSslDrawableForState(sslState))
            binding.searchSslStatus.visibility = View.VISIBLE
        }
        viewState.pageUrl?.let { url ->
            currentPageUrl = url
            if (currentSslState is SslState.Valid) {
                triggerCertimarkCheck(url)
            } else {
                certimarkJob?.cancel()
                certimarkResult = null
                lastCheckedDomain = null
            }
        }
        viewState.enableFullMenu?.let {
            menuItemShare?.isVisible = it
            menuItemCopyLink?.isVisible = it
            menuItemAddToHome?.isVisible = it
            menuItemAddBookmark?.isVisible = it
        }
        viewState.themeColor?.value()?.let(::animateColorChange)
        viewState.progress?.let {
            binding.progressView.isVisible = it != 100
            binding.progressView.progress = it
        }
        viewState.isRefresh?.let {
            binding.searchRefresh.setImageResource(
                if (it) {
                    R.drawable.ic_action_refresh
                } else {
                    R.drawable.ic_action_delete
                }
            )
        }
        viewState.bookmarks?.let(bookmarksAdapter::submitList)
        viewState.isBookmarked?.let { binding.actionAddBookmark.isSelected = it }
        viewState.isBookmarkEnabled?.let { binding.actionAddBookmark.isEnabled = it }
        viewState.isRootFolder?.let {
            binding.bookmarkBackButton.startAnimation(
                AnimationUtils.createRotationTransitionAnimation(
                    binding.bookmarkBackButton,
                    if (it) {
                        R.drawable.ic_action_star
                    } else {
                        R.drawable.ic_action_back
                    }
                )
            )
        }
        viewState.findInPage?.let {
            if (it.isEmpty()) {
                binding.findBar.isVisible = false
            } else {
                binding.findBar.isVisible = true
                binding.findQuery.text = it
            }
        }
    }

    /**
     * @see BrowserContract.View.renderTabs
     */
    fun renderTabs(tabListState: List<TabViewState>) {
        binding.tabCountView.updateCount(tabListState.size)
        val shouldScroll = tabsAdapter.itemCount < tabListState.size
        tabsAdapter.submitList(tabListState)
        val nextSelected = tabListState.indexOfFirst(TabViewState::isSelected)
        if (shouldScroll && nextSelected != -1) {
            mainHandler.post {
                if (tabPager.isBottomTabDrawerOpen()) {
                    activeRecyclerView?.smoothScrollToPosition(nextSelected)
                } else {
                    pendingScroll = nextSelected
                }
            }
        }
    }

    /**
     * @see BrowserContract.View.showAddBookmarkDialog
     */
    fun showAddBookmarkDialog(title: String, url: String, folders: List<String>) {
        lightningDialogBuilder.showAddBookmarkDialog(
            activity = this,
            currentTitle = title,
            currentUrl = url,
            folders = folders,
            onSave = presenter::onBookmarkConfirmed
        )
    }

    /**
     * @see BrowserContract.View.showBookmarkOptionsDialog
     */
    fun showBookmarkOptionsDialog(bookmark: Bookmark.Entry) {
        lightningDialogBuilder.showLongPressedDialogForBookmarkUrl(
            activity = this,
            onClick = {
                presenter.onBookmarkOptionClick(bookmark, it)
            }
        )
    }

    /**
     * @see BrowserContract.View.showEditBookmarkDialog
     */
    fun showEditBookmarkDialog(title: String, url: String, folder: String, folders: List<String>) {
        lightningDialogBuilder.showEditBookmarkDialog(
            activity = this,
            currentTitle = title,
            currentUrl = url,
            currentFolder = folder,
            folders = folders,
            onSave = presenter::onBookmarkEditConfirmed
        )
    }

    /**
     * @see BrowserContract.View.showFolderOptionsDialog
     */
    fun showFolderOptionsDialog(folder: Bookmark.Folder) {
        lightningDialogBuilder.showBookmarkFolderLongPressedDialog(
            activity = this,
            onClick = {
                presenter.onFolderOptionClick(folder, it)
            }
        )
    }

    /**
     * @see BrowserContract.View.showEditFolderDialog
     */
    fun showEditFolderDialog(oldTitle: String) {
        lightningDialogBuilder.showRenameFolderDialog(
            activity = this,
            oldTitle = oldTitle,
            onSave = presenter::onBookmarkFolderRenameConfirmed
        )
    }

    /**
     * @see BrowserContract.View.showDownloadOptionsDialog
     */
    fun showDownloadOptionsDialog(download: DownloadEntry) {
        lightningDialogBuilder.showLongPressedDialogForDownloadUrl(
            activity = this,
            onClick = {
                presenter.onDownloadOptionClick(download, it)
            }
        )
    }

    /**
     * @see BrowserContract.View.showHistoryOptionsDialog
     */
    fun showHistoryOptionsDialog(historyEntry: HistoryEntry) {
        lightningDialogBuilder.showLongPressedHistoryLinkDialog(
            activity = this,
            onClick = {
                presenter.onHistoryOptionClick(historyEntry, it)
            }
        )
    }

    /**
     * @see BrowserContract.View.showFindInPageDialog
     */
    fun showFindInPageDialog() {
        BrowserDialog.showEditText(
            this,
            R.string.action_find,
            R.string.search_hint,
            R.string.search_hint,
            presenter::onFindInPage
        )
    }

    /**
     * @see BrowserContract.View.showLinkLongPressDialog
     */
    fun showLinkLongPressDialog(longPress: LongPress) {
        BrowserDialog.show(
            this, longPress.targetUrl?.replace(HTTP, ""),
            DialogItem(title = R.string.dialog_open_new_tab) {
                presenter.onLinkLongPressEvent(
                    longPress,
                    BrowserContract.LinkLongPressEvent.NEW_TAB
                )
            },
            DialogItem(title = R.string.dialog_open_background_tab) {
                presenter.onLinkLongPressEvent(
                    longPress,
                    BrowserContract.LinkLongPressEvent.BACKGROUND_TAB
                )
            },
            DialogItem(
                title = R.string.dialog_open_incognito_tab,
                isConditionMet = !isIncognito()
            ) {
                presenter.onLinkLongPressEvent(
                    longPress,
                    BrowserContract.LinkLongPressEvent.INCOGNITO_TAB
                )
            },
            DialogItem(title = R.string.action_share) {
                presenter.onLinkLongPressEvent(longPress, BrowserContract.LinkLongPressEvent.SHARE)
            },
            DialogItem(title = R.string.dialog_copy_link) {
                presenter.onLinkLongPressEvent(
                    longPress,
                    BrowserContract.LinkLongPressEvent.COPY_LINK
                )
            })
    }

    /**
     * @see BrowserContract.View.showImageLongPressDialog
     */
    fun showImageLongPressDialog(longPress: LongPress) {
        BrowserDialog.show(
            this, longPress.targetUrl?.replace(HTTP, ""),
            DialogItem(title = R.string.dialog_open_new_tab) {
                presenter.onImageLongPressEvent(
                    longPress,
                    BrowserContract.ImageLongPressEvent.NEW_TAB
                )
            },
            DialogItem(title = R.string.dialog_open_background_tab) {
                presenter.onImageLongPressEvent(
                    longPress,
                    BrowserContract.ImageLongPressEvent.BACKGROUND_TAB
                )
            },
            DialogItem(
                title = R.string.dialog_open_incognito_tab,
                isConditionMet = !isIncognito()
            ) {
                presenter.onImageLongPressEvent(
                    longPress,
                    BrowserContract.ImageLongPressEvent.INCOGNITO_TAB
                )
            },
            DialogItem(title = R.string.action_share) {
                presenter.onImageLongPressEvent(
                    longPress,
                    BrowserContract.ImageLongPressEvent.SHARE
                )
            },
            DialogItem(title = R.string.dialog_copy_link) {
                presenter.onImageLongPressEvent(
                    longPress,
                    BrowserContract.ImageLongPressEvent.COPY_LINK
                )
            },
            DialogItem(title = R.string.dialog_download_image) {
                presenter.onImageLongPressEvent(
                    longPress,
                    BrowserContract.ImageLongPressEvent.DOWNLOAD
                )
            })
    }

    /**
     * @see BrowserContract.View.showCloseBrowserDialog
     */
    fun showCloseBrowserDialog(id: Int) {
        BrowserDialog.show(
            this, R.string.dialog_title_close_browser,
            DialogItem(title = R.string.close_tab) {
                presenter.onCloseBrowserEvent(id, BrowserContract.CloseTabEvent.CLOSE_CURRENT)
            },
            DialogItem(title = R.string.close_other_tabs) {
                presenter.onCloseBrowserEvent(id, BrowserContract.CloseTabEvent.CLOSE_OTHERS)
            },
            DialogItem(title = R.string.close_all_tabs, onClick = {
                presenter.onCloseBrowserEvent(id, BrowserContract.CloseTabEvent.CLOSE_ALL)
            })
        )
    }

    /**
     * @see BrowserContract.View.openBookmarkDrawer
     */
    fun openBookmarkDrawer() {
        binding.drawerLayout.closeDrawer(binding.tabDrawer)
        binding.drawerLayout.openDrawer(binding.bookmarkDrawer)
    }

    /**
     * @see BrowserContract.View.closeBookmarkDrawer
     */
    fun closeBookmarkDrawer() {
        binding.drawerLayout.closeDrawer(binding.bookmarkDrawer)
    }

    /**
     * @see BrowserContract.View.openTabDrawer
     */
    fun openTabDrawer() {
        binding.drawerLayout.closeDrawer(binding.bookmarkDrawer)
        if (binding.browserLayoutContainer == null) {
            binding.drawerLayout.openDrawer(binding.tabDrawer)
        } else {
            presenter.onTabDrawerMoved(isOpen = true)
            tabPager.openBottomTabDrawer()
            if (pendingScroll != -1) {
                activeRecyclerView?.scrollToPosition(pendingScroll)
                pendingScroll = -1
            }
        }
    }

    /**
     * @see BrowserContract.View.closeTabDrawer
     */
    fun closeTabDrawer() {
        if (binding.browserLayoutContainer == null) {
            binding.drawerLayout.closeDrawer(binding.tabDrawer)
        } else {
            presenter.onTabDrawerMoved(isOpen = false)
            tabPager.closeBottomTabDrawer()
        }
    }

    /**
     * @see BrowserContract.View.showToolbar
     */
    fun showToolbar() {
        tabPager.showToolbar()
    }

    /**
     * @see BrowserContract.View.showToolsDialog
     */
    fun showToolsDialog(areAdsAllowed: Boolean, shouldShowAdBlockOption: Boolean) {
        val whitelistString = if (areAdsAllowed) {
            R.string.dialog_adblock_enable_for_site
        } else {
            R.string.dialog_adblock_disable_for_site
        }

        BrowserDialog.showWithIcons(
            this, getString(R.string.dialog_tools_title),
            DialogItem(
                icon = drawable(R.drawable.ic_action_desktop),
                title = R.string.dialog_toggle_desktop,
                onClick = presenter::onToggleDesktopAgent
            ),
            DialogItem(
                icon = drawable(R.drawable.ic_block),
                colorTint = color(R.color.error_red).takeIf { areAdsAllowed },
                title = whitelistString,
                isConditionMet = shouldShowAdBlockOption,
                onClick = presenter::onToggleAdBlocking
            )
        )
    }

    /**
     * @see BrowserContract.View.showLocalFileBlockedDialog
     */
    fun showLocalFileBlockedDialog() {
        AlertDialog.Builder(this)
            .setCancelable(true)
            .setTitle(R.string.title_warning)
            .setMessage(R.string.message_blocked_local)
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                presenter.onConfirmOpenLocalFile(allow = false)
            }
            .setPositiveButton(R.string.action_open) { _, _ ->
                presenter.onConfirmOpenLocalFile(allow = true)
            }
            .setOnCancelListener { presenter.onConfirmOpenLocalFile(allow = false) }
            .resizeAndShow()
    }

    /**
     * @see BrowserContract.View.showFileChooser
     */
    fun showFileChooser(intent: Intent) {
        launcher.launch(intent)
    }

    /**
     * @see BrowserContract.View.showCustomView
     */
    fun showCustomView(view: View) {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
        binding.root.addView(view)
        customView = view
        setFullscreen(enabled = true, immersive = true)
    }

    /**
     * @see BrowserContract.View.hideCustomView
     */
    fun hideCustomView() {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        customView?.let(binding.root::removeView)
        customView = null
        setFullscreen(enabled = false, immersive = false)
    }

    /**
     * @see BrowserContract.View.clearSearchFocus
     */
    fun clearSearchFocus() {
        binding.search.clearFocus()
    }

    // TODO: update to use non deprecated flags
    private fun setFullscreen(enabled: Boolean, immersive: Boolean) {
        val window = window
        val decor = window.decorView
        if (enabled) {
            if (immersive) {
                decor.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
            } else {
                decor.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            }
            window.setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
            )
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            decor.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    private fun animateColorChange(color: Int) {
        if (!userPreferencesDataStore.colorModeEnabled.getUnsafe() || userPreferencesDataStore.useTheme.getUnsafe() != AppTheme.LIGHT || isIncognito()) {
            return
        }
        val adapter = tabsAdapter as? DesktopTabRecyclerViewAdapter
        val colorAnimator = ColorAnimator(defaultColor)
        binding.toolbar.startAnimation(
            colorAnimator.animateTo(
                color
            ) { mainColor, secondaryColor ->
                if (userPreferencesDataStore.tabConfiguration.getUnsafe() != TabConfiguration.DESKTOP) {
                    backgroundDrawable.color = mainColor
                    window.setBackgroundDrawable(backgroundDrawable)
                } else {
                    adapter?.updateForegroundTabColor(mainColor)
                }
                binding.toolbar.setBackgroundColor(mainColor)
                binding.searchContainer.background?.tint(secondaryColor)
            })
    }

    private fun ImageView.updateVisibilityForDrawable() {
        visibility = if (drawable == null) {
            View.GONE
        } else {
            View.VISIBLE
        }
    }
}
