package com.example

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.example.ui.theme.MyApplicationTheme
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import com.google.firebase.storage.FirebaseStorage

// --- ViewModels & Data ---
data class User(val id: String, val name: String, val status: String, val lastMessage: String, val time: String, val isOnline: Boolean, val profileUrl: String? = null)
data class Message(val id: Int, val text: String, val isSender: Boolean, val timestamp: String)

class ChatViewModel : ViewModel() {
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseDatabase.getInstance().reference

    private val _contacts = MutableStateFlow<List<User>>(emptyList())
    val contacts: StateFlow<List<User>> = _contacts.asStateFlow()

    private val _messages = MutableStateFlow<Map<String, List<Message>>>(emptyMap())
    val messages: StateFlow<Map<String, List<Message>>> = _messages.asStateFlow()

    private val _isDarkTheme = MutableStateFlow<Boolean?>(null)
    val isDarkTheme: StateFlow<Boolean?> = _isDarkTheme.asStateFlow()
    
    fun setTheme(isDark: Boolean?) {
        _isDarkTheme.value = isDark
    }

    init {
        startListening()
    }

    private fun startListening() {
        val currentUserId = auth.currentUser?.uid ?: return
        
        db.child("friends").child(currentUserId).addValueEventListener(object : ValueEventListener {
            override fun onDataChange(friendsSnapshot: DataSnapshot) {
                val friendIds = friendsSnapshot.children.mapNotNull { it.key }
                
                db.child("users").addListenerForSingleValueEvent(object: ValueEventListener {
                    override fun onDataChange(usersSnapshot: DataSnapshot) {
                        val userList = mutableListOf<User>()
                        for (uid in friendIds) {
                            val child = usersSnapshot.child(uid)
                            if (child.exists()) {
                                val name = child.child("name").getValue(String::class.java) ?: "Anonymous"
                                val profileUrl = child.child("profileUrl").getValue(String::class.java)
                                userList.add(User(uid, name, "Available", "Click to chat", "", true, profileUrl))
                            }
                        }
                        // Also listen for general users update? We will just update contacts
                        _contacts.value = userList
                    }
                    override fun onCancelled(error: DatabaseError) {}
                })
            }
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    fun signUp(email: String, pass: String, name: String, onComplete: (Boolean, String?) -> Unit) {
        auth.createUserWithEmailAndPassword(email.trim(), pass).addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val user = auth.currentUser
                if (user != null) {
                    db.child("users").child(user.uid).child("name").setValue(name.ifBlank { "User" })
                    db.child("users").child(user.uid).child("email").setValue(email)
                    startListening()
                    onComplete(true, null)
                } else {
                    onComplete(false, "Unknown error")
                }
            } else {
                onComplete(false, task.exception?.message)
            }
        }
    }

    fun signIn(email: String, pass: String, onComplete: (Boolean, String?) -> Unit) {
        auth.signInWithEmailAndPassword(email.trim(), pass).addOnCompleteListener { task ->
            if (task.isSuccessful) {
                startListening()
                onComplete(true, null)
            } else {
                onComplete(false, task.exception?.message)
            }
        }
    }

    fun addContact(friendEmailOrName: String, onResult: (Boolean, String) -> Unit) {
        val currentUserId = auth.currentUser?.uid ?: return
        val queryStr = friendEmailOrName.trim()
        
        db.child("users").orderByChild("email").equalTo(queryStr).addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (snapshot.exists()) {
                    for (child in snapshot.children) {
                        db.child("friends").child(currentUserId).child(child.key ?: "").setValue(true)
                        db.child("friends").child(child.key ?: "").child(currentUserId).setValue(true) // Mutual
                        onResult(true, "Friend added successfully.")
                        return
                    }
                } else {
                    // try query by name instead
                    db.child("users").orderByChild("name").equalTo(queryStr).addListenerForSingleValueEvent(object : ValueEventListener {
                        override fun onDataChange(snapshot2: DataSnapshot) {
                            if (snapshot2.exists()) {
                                for (child in snapshot2.children) {
                                    db.child("friends").child(currentUserId).child(child.key ?: "").setValue(true)
                                    db.child("friends").child(child.key ?: "").child(currentUserId).setValue(true)
                                    onResult(true, "Friend added successfully.")
                                    return
                                }
                            } else {
                                onResult(false, "User not found.")
                            }
                        }
                        override fun onCancelled(error: DatabaseError) {
                            onResult(false, "Error: ${error.message}")
                        }
                    })
                }
            }
            override fun onCancelled(error: DatabaseError) {
                onResult(false, "Error: ${error.message}")
            }
        })
    }

    fun listenForMessages(chatPartnerId: String) {
        val currentUserId = auth.currentUser?.uid ?: return
        val roomId = if (currentUserId < chatPartnerId) "${currentUserId}_$chatPartnerId" else "${chatPartnerId}_$currentUserId"

        db.child("messages").child(roomId).addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val msgs = mutableListOf<Message>()
                val cutoffTime = System.currentTimeMillis() - 24 * 60 * 60 * 1000 // 24 hours
                
                for (child in snapshot.children) {
                    val timestampLong = child.child("timestamp").getValue(Long::class.java) ?: 0L
                    
                    // Auto-delete simulation or reality
                    if (timestampLong > 0 && timestampLong < cutoffTime) {
                        child.ref.removeValue()
                        continue
                    }
                    
                    val text = child.child("text").getValue(String::class.java) ?: ""
                    val senderId = child.child("senderId").getValue(String::class.java) ?: ""
                    val timestampStr = child.child("timestampStr").getValue(String::class.java) ?: ""
                    
                    val isSender = senderId == currentUserId
                    msgs.add(Message(msgs.size + 1, text, isSender, timestampStr))
                }
                
                _messages.update { currentMap ->
                    val newMap = currentMap.toMutableMap()
                    newMap[chatPartnerId] = msgs.reversed()
                    newMap
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    fun sendMessage(chatPartnerId: String, text: String) {
        val currentUserId = auth.currentUser?.uid ?: return
        val roomId = if (currentUserId < chatPartnerId) "${currentUserId}_$chatPartnerId" else "${chatPartnerId}_$currentUserId"

        val timestampStr = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
        val msgRef = db.child("messages").child(roomId).push()
        val msgData = mapOf(
            "senderId" to currentUserId,
            "text" to text,
            "timestampStr" to timestampStr,
            "timestamp" to ServerValue.TIMESTAMP
        )
        msgRef.setValue(msgData)
    }
}

// --- MainActivity ---
class MainActivity : ComponentActivity() {
    private val viewModel: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val isDarkTheme by viewModel.isDarkTheme.collectAsStateWithLifecycle()
            MyApplicationTheme(darkTheme = isDarkTheme ?: androidx.compose.foundation.isSystemInDarkTheme()) {
                AppNavigation(viewModel)
            }
        }
    }
}

@Composable
fun AppNavigation(viewModel: ChatViewModel) {
    val navController = rememberNavController()
    val auth = FirebaseAuth.getInstance()
    val startDest = if (auth.currentUser != null) "main" else "login"
    
    NavHost(
        navController = navController,
        startDestination = startDest
    ) {
        composable("login") { LoginScreen(navController, viewModel) }
        composable("main") { MainScreen(navController, viewModel) }
        composable(
            "chat/{userId}",
            arguments = listOf(navArgument("userId") { type = NavType.StringType })
        ) { backStackEntry ->
            val userId = backStackEntry.arguments?.getString("userId") ?: ""
            ChatScreen(navController, viewModel, userId)
        }
        composable("settings_account") { AccountSettingsScreen(navController) }
        composable("settings_appearance") { AppearanceSettingsScreen(navController, viewModel) }
        composable("settings_notifications") { NotificationsSettingsScreen(navController) }
    }
}

// --- Screens ---
@Composable
fun LoginScreen(navController: NavController, viewModel: ChatViewModel) {
    var isSignUp by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    val context = LocalContext.current
    
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                Icons.Default.Email,
                contentDescription = "Logo",
                modifier = Modifier.size(60.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text("Welcome to SpiriteX", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            Text(if (isSignUp) "Create an account" else "Sign in to continue", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
            Spacer(modifier = Modifier.height(32.dp))
            
            if (isSignUp) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Display Name") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
            
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Email") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(16.dp))
            
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation()
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            if (!isSignUp) {
                TextButton(
                    onClick = {
                        if (email.isBlank()) {
                            Toast.makeText(context, "Please enter your email to reset password", Toast.LENGTH_SHORT).show()
                            return@TextButton
                        }
                        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                            Toast.makeText(context, "Please enter a valid email address", Toast.LENGTH_SHORT).show()
                            return@TextButton
                        }
                        FirebaseAuth.getInstance().sendPasswordResetEmail(email)
                            .addOnCompleteListener { task ->
                                if (task.isSuccessful) {
                                    Toast.makeText(context, "Password reset link sent to email", Toast.LENGTH_LONG).show()
                                } else {
                                    Toast.makeText(context, "Error: ${task.exception?.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                    }
                ) {
                    Text("Forgot Password?", color = MaterialTheme.colorScheme.primary)
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Button(
                onClick = { 
                    if (email.isBlank() || password.isBlank() || (isSignUp && name.isBlank())) {
                        Toast.makeText(context, "Please fill all fields", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                        Toast.makeText(context, "Please enter a valid email address", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    isLoading = true
                    val callback = { success: Boolean, msg: String? ->
                        isLoading = false
                        if (success) {
                            navController.navigate("main") { popUpTo("login") { inclusive = true } }
                        } else {
                            Toast.makeText(context, msg ?: "Authentication failed", Toast.LENGTH_SHORT).show()
                        }
                    }
                    if (isSignUp) {
                        viewModel.signUp(email, password, name, callback)
                    } else {
                        viewModel.signIn(email, password, callback)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(12.dp),
                enabled = !isLoading
            ) {
                if (isLoading) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(24.dp))
                } else {
                    Text(if (isSignUp) "Sign Up" else "Sign In", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            TextButton(onClick = { isSignUp = !isSignUp }) {
                Text(if (isSignUp) "Already have an account? Sign In" else "Don't have an account? Sign Up")
            }
        }
    }
}

@Composable
fun MainScreen(navController: NavController, viewModel: ChatViewModel) {
    var selectedTab by remember { mutableStateOf(0) }
    
    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Email, contentDescription = "Chats") },
                    label = { Text("Chats") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.Person, contentDescription = "Contacts") },
                    label = { Text("Contacts") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                    label = { Text("Settings") }
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when (selectedTab) {
                0 -> ChatListTab(navController, viewModel)
                1 -> ContactsTab(navController, viewModel)
                2 -> ProfileTab(navController)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListTab(navController: NavController, viewModel: ChatViewModel) {
    val contacts by viewModel.contacts.collectAsStateWithLifecycle()
    
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopAppBar(
            title = { Text("Chats", fontWeight = FontWeight.Bold) },
            actions = {
                IconButton(onClick = { /* Search */ }) {
                    Icon(Icons.Default.Search, contentDescription = "Search")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
        )
        
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(contacts, key = { it.id }) { user ->
                ChatListItem(user) {
                    navController.navigate("chat/${user.id}")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsTab(navController: NavController, viewModel: ChatViewModel) {
    val contacts by viewModel.contacts.collectAsStateWithLifecycle()
    var searchQuery by remember { mutableStateOf("") }
    var showAddDialog by remember { mutableStateOf(false) }
    
    if (showAddDialog) {
        var newFriendName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Add Friend") },
            text = {
                OutlinedTextField(
                    value = newFriendName,
                    onValueChange = { newFriendName = it },
                    label = { Text("Username or Email") },
                    singleLine = true
                )
            },
            confirmButton = {
                val context = LocalContext.current
                TextButton(onClick = {
                    if (newFriendName.isNotBlank()) {
                        viewModel.addContact(newFriendName) { success, msg ->
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        }
                    }
                    showAddDialog = false
                }) { Text("Add") }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text("Cancel") }
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopAppBar(
            title = { Text("Contacts", fontWeight = FontWeight.Bold) },
            actions = {
                IconButton(onClick = { showAddDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = "Add Contact")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
        )
        
        // Search bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search friends...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(24.dp),
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = Color.Transparent,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        )
        
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            val filtered = contacts.filter { it.name.contains(searchQuery, ignoreCase = true) }
            items(filtered, key = { it.id }) { user ->
                ContactListItem(user) {
                    navController.navigate("chat/${user.id}")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileTab(navController: NavController) {
    val auth = FirebaseAuth.getInstance()
    var currentName by remember { mutableStateOf("User") }
    var profileUrl by remember { mutableStateOf<String?>(null) }
    var isUploading by remember { mutableStateOf(false) }
    val initial = currentName.firstOrNull()?.uppercase() ?: "U"

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            val uid = auth.currentUser?.uid ?: return@rememberLauncherForActivityResult
            isUploading = true
            val ref = FirebaseStorage.getInstance().reference.child("profiles/$uid.jpg")
            ref.putFile(uri).addOnSuccessListener {
                ref.downloadUrl.addOnSuccessListener { downloadUri ->
                    FirebaseDatabase.getInstance().reference.child("users").child(uid).child("profileUrl").setValue(downloadUri.toString())
                    profileUrl = downloadUri.toString()
                    isUploading = false
                }
            }.addOnFailureListener {
                isUploading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        val uid = auth.currentUser?.uid
        if (uid != null) {
            FirebaseDatabase.getInstance().reference.child("users").child(uid).get().addOnSuccessListener {
                currentName = it.child("name").value as? String ?: "User"
                profileUrl = it.child("profileUrl").value as? String
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopAppBar(
            title = { Text("Settings", fontWeight = FontWeight.Bold) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
        )
        
        Column(modifier = Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .clickable { launcher.launch("image/*") },
                contentAlignment = Alignment.Center
            ) {
                if (isUploading) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(24.dp))
                } else if (profileUrl != null) {
                    AsyncImage(
                        model = profileUrl,
                        contentDescription = "Profile",
                        modifier = Modifier.fillMaxSize().clip(CircleShape),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Text(initial, fontSize = 40.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(currentName, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            Text(auth.currentUser?.email ?: "Available", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.surfaceVariant)
        
        ListItem(
            headlineContent = { Text("Account & Privacy") },
            supportingContent = { Text("Encryption, auto-delete, change number") },
            leadingContent = { Icon(Icons.Default.Lock, contentDescription = null) },
            modifier = Modifier.clickable { navController.navigate("settings_account") }
        )
        ListItem(
            headlineContent = { Text("Appearance") },
            supportingContent = { Text("Theme, wallpapers, chat colors") },
            leadingContent = { Icon(Icons.Default.Edit, contentDescription = null) },
            modifier = Modifier.clickable { navController.navigate("settings_appearance") }
        )
        ListItem(
            headlineContent = { Text("Notifications") },
            supportingContent = { Text("Message, group & call tones") },
            leadingContent = { Icon(Icons.Default.Notifications, contentDescription = null) },
            modifier = Modifier.clickable { navController.navigate("settings_notifications") }
        )
        
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.surfaceVariant)
        
        ListItem(
            headlineContent = { Text("Log Out", color = MaterialTheme.colorScheme.error) },
            leadingContent = { Icon(Icons.Default.ExitToApp, contentDescription = "Log Out", tint = MaterialTheme.colorScheme.error) },
            modifier = Modifier.clickable { 
                FirebaseAuth.getInstance().signOut()
                navController.navigate("login") { popUpTo(0) }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSettingsScreen(navController: NavController) {
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Account & Privacy") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp)) {
            Text("End-to-End Encryption", fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Text("Your messages are secured mathematically. Only you and the recipient can read them.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
            Spacer(modifier = Modifier.height(24.dp))
            Text("Data Retention", fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Text("All messages are automatically deleted mathematically and permanently from servers within 24 hours.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
            Spacer(modifier = Modifier.height(24.dp))
            ListItem(headlineContent = { Text("Blocked Contacts") }, modifier = Modifier.clickable { Toast.makeText(context, "No blocked contacts", Toast.LENGTH_SHORT).show() })
            ListItem(headlineContent = { Text("Change Number") }, modifier = Modifier.clickable { Toast.makeText(context, "Feature coming soon", Toast.LENGTH_SHORT).show() })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceSettingsScreen(navController: NavController, viewModel: ChatViewModel) {
    val isDarkTheme by viewModel.isDarkTheme.collectAsStateWithLifecycle()
    var showThemeDialog by remember { mutableStateOf(false) }

    if (showThemeDialog) {
        AlertDialog(
            onDismissRequest = { showThemeDialog = false },
            title = { Text("Choose Theme") },
            text = {
                Column {
                    ListItem(
                        headlineContent = { Text("System Default") },
                        modifier = Modifier.clickable { viewModel.setTheme(null); showThemeDialog = false }
                    )
                    ListItem(
                        headlineContent = { Text("Light") },
                        modifier = Modifier.clickable { viewModel.setTheme(false); showThemeDialog = false }
                    )
                    ListItem(
                        headlineContent = { Text("Dark") },
                        modifier = Modifier.clickable { viewModel.setTheme(true); showThemeDialog = false }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showThemeDialog = false }) { Text("Close") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Appearance") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp)) {
            val themeText = when(isDarkTheme) {
                true -> "Dark"
                false -> "Light"
                else -> "System Default"
            }
            ListItem(headlineContent = { Text("Theme") }, supportingContent = { Text(themeText) }, modifier = Modifier.clickable { showThemeDialog = true })
            ListItem(headlineContent = { Text("Chat Wallpaper") }, modifier = Modifier.clickable { })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsSettingsScreen(navController: NavController) {
    var messagesEnabled by remember { mutableStateOf(true) }
    var callsEnabled by remember { mutableStateOf(true) }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Notifications") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp)) {
            ListItem(
                headlineContent = { Text("Message Notifications") },
                trailingContent = { Switch(checked = messagesEnabled, onCheckedChange = { messagesEnabled = it }) }
            )
            ListItem(
                headlineContent = { Text("Call Notifications") },
                trailingContent = { Switch(checked = callsEnabled, onCheckedChange = { callsEnabled = it }) }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(navController: NavController, viewModel: ChatViewModel, userId: String) {
    val contacts by viewModel.contacts.collectAsStateWithLifecycle()
    val allMessages by viewModel.messages.collectAsStateWithLifecycle()
    
    val user = contacts.find { it.id == userId } ?: User(userId, "Unknown", "Unknown", "", "", false)
    val messages = allMessages[userId] ?: emptyList()
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(userId) {
        viewModel.listenForMessages(userId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ProfileAvatar(user.name, user.profileUrl, user.isOnline, size = 36.dp)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(user.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            Text(if (user.isOnline) "Online" else "Offline", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { }) { Icon(Icons.Default.Call, contentDescription = "Call") }
                    IconButton(onClick = { }) { Icon(Icons.Default.Info, contentDescription = "Info") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                reverseLayout = true,
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages, key = { it.id }) { message ->
                    ChatBubble(message)
                }
            }
            
            // Input Area
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { }) {
                    Icon(Icons.Default.Add, contentDescription = "Attach", tint = MaterialTheme.colorScheme.primary)
                }
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    placeholder = { Text("Message") },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = Color.Transparent,
                        focusedBorderColor = Color.Transparent,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    maxLines = 4
                )
                IconButton(
                    onClick = {
                        if (inputText.isNotBlank()) {
                            viewModel.sendMessage(userId, inputText)
                            inputText = ""
                            coroutineScope.launch {
                                listState.animateScrollToItem(0)
                            }
                        }
                    },
                    modifier = Modifier.padding(start = 4.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary)
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }
    }
}

// --- Components ---
@Composable
fun ChatListItem(user: User, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ProfileAvatar(user.name, user.profileUrl, user.isOnline, size = 52.dp)
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(user.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
                    Text(user.time, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(user.lastMessage, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun ContactListItem(user: User, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ProfileAvatar(user.name, user.profileUrl, user.isOnline, size = 48.dp)
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(user.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
                Text(user.status, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            IconButton(onClick = onClick) {
                Icon(Icons.Default.Email, contentDescription = "Chat", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
fun ProfileAvatar(name: String, profileUrl: String?, isOnline: Boolean, size: androidx.compose.ui.unit.Dp) {
    Box(modifier = Modifier.size(size)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            if (profileUrl != null) {
                AsyncImage(
                    model = profileUrl,
                    contentDescription = name,
                    modifier = Modifier.fillMaxSize().clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
            } else {
                Text(name.firstOrNull()?.toString() ?: "U", fontSize = (size.value * 0.4).sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
        if (isOnline) {
            Box(
                modifier = Modifier
                    .size(size * 0.28f)
                    .align(Alignment.BottomEnd)
                    .clip(CircleShape)
                    .background(Color.Green)
                    .border(2.dp, MaterialTheme.colorScheme.background, CircleShape)
            )
        }
    }
}

@Composable
fun ChatBubble(message: Message, modifier: Modifier = Modifier) {
    val alignment = if (message.isSender) Alignment.CenterEnd else Alignment.CenterStart
    val bubbleColor = if (message.isSender) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (message.isSender) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    val shape = if (message.isSender) {
        RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp)
    } else {
        RoundedCornerShape(20.dp, 20.dp, 20.dp, 4.dp)
    }
    
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = alignment) {
        Column(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .background(bubbleColor, shape)
                .padding(12.dp)
        ) {
            Text(
                text = message.text,
                color = textColor,
                fontSize = 15.sp,
                lineHeight = 20.sp
            )
            Row(modifier = Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                Text(message.timestamp, fontSize = 10.sp, color = textColor.copy(alpha = 0.7f))
                if (message.isSender) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(Icons.Default.Done, contentDescription = "Read", tint = textColor.copy(alpha = 0.9f), modifier = Modifier.size(12.dp))
                }
            }
        }
    }
}
