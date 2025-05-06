package com.example.penguin;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import com.example.penguin.chatmodel.Message;
import com.example.penguin.chatmodel.MessageAdapter;
import com.google.android.material.navigation.NavigationView;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity {

    RecyclerView recyclerView;
    EditText message_text_text;
    ImageView send_btn;
    ImageView menu_btn;
    List<Message> messageList = new ArrayList<>();
    MessageAdapter messageAdapter;

    private DrawerLayout drawerLayout;
    private NavigationView navigationView;
    private RecyclerView historyRecyclerView;
    private List<String> chatHistoryList = new ArrayList<>();
    private ChatHistoryAdapter historyAdapter;

    public static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    OkHttpClient client = new OkHttpClient();

    FirebaseAuth firebaseAuth;
    FirebaseUser firebaseUser;
    DatabaseReference databaseReference;
    Button logoutBtn;
    TextView userEmailText;

    private String currentChatId = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Firebase initialization
        firebaseAuth = FirebaseAuth.getInstance();
        firebaseUser = firebaseAuth.getCurrentUser();
        databaseReference = FirebaseDatabase.getInstance().getReference("chatHistory");

        if (firebaseUser == null) {
            startActivity(new Intent(MainActivity.this, LoginActivity.class));
            finish();
            return;
        }

        initializeUI();


        setupNavigationDrawer();


        loadChatHistory();

        setupChatFunctionality();

    }

    private void initializeUI() {
        userEmailText = findViewById(R.id.userEmailText);
        logoutBtn = findViewById(R.id.logoutBtn);
        message_text_text = findViewById(R.id.message_text_text);
        send_btn = findViewById(R.id.send_btn);
        menu_btn = findViewById(R.id.menu_btn);
        recyclerView = findViewById(R.id.recyclerView);

        userEmailText.setText("Logged in as: " + firebaseUser.getEmail());

        LinearLayoutManager linearLayoutManager = new LinearLayoutManager(this);
        linearLayoutManager.setStackFromEnd(true);
        recyclerView.setLayoutManager(linearLayoutManager);

        messageAdapter = new MessageAdapter(messageList);
        recyclerView.setAdapter(messageAdapter);
    }

    private void setupNavigationDrawer() {
        drawerLayout = findViewById(R.id.drawer_layout);
        navigationView = findViewById(R.id.nav_view);

        // Initialize history RecyclerView inside the drawer
        View headerView = navigationView.getHeaderView(0);
        historyRecyclerView = headerView.findViewById(R.id.historyRecyclerView);
        historyRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        historyAdapter = new ChatHistoryAdapter(chatHistoryList, this::loadSelectedChat);
        historyRecyclerView.setAdapter(historyAdapter);

        menu_btn.setOnClickListener(v -> drawerLayout.openDrawer(Gravity.START));

        logoutBtn.setOnClickListener(v -> {
            firebaseAuth.signOut();
            Intent intent = new Intent(MainActivity.this, LoginActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            finish();
        });
    }

    private void loadChatHistory() {
        if (firebaseUser != null) {
            String userId = firebaseUser.getUid();
            databaseReference.child(userId).addValueEventListener(new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot dataSnapshot) {
                    chatHistoryList.clear();
                    for (DataSnapshot snapshot : dataSnapshot.getChildren()) {
                        String chatTitle = snapshot.getKey();
                        chatHistoryList.add(chatTitle);
                    }
                    historyAdapter.notifyDataSetChanged();
                }

                @Override
                public void onCancelled(@NonNull DatabaseError databaseError) {
                    Log.e("Firebase", "Error loading chat history", databaseError.toException());
                }
            });
        }
    }

    private void loadSelectedChat(String chatId) {
        if (firebaseUser != null) {
            String userId = firebaseUser.getUid();
            databaseReference.child(userId).child(chatId).addListenerForSingleValueEvent(new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot dataSnapshot) {
                    messageList.clear();
                    for (DataSnapshot snapshot : dataSnapshot.getChildren()) {
                        Message message = snapshot.getValue(Message.class);
                        if (message != null) {
                            messageList.add(message);
                        }
                    }
                    messageAdapter.notifyDataSetChanged();
                    recyclerView.smoothScrollToPosition(messageList.size() - 1);
                    drawerLayout.closeDrawer(Gravity.START);
                }

                @Override
                public void onCancelled(@NonNull DatabaseError databaseError) {
                    Log.e("Firebase", "Error loading chat", databaseError.toException());
                }
            });
        }
    }

    private void setupChatFunctionality() {
        send_btn.setOnClickListener(view -> {
            String question = message_text_text.getText().toString().trim();
            if (!question.isEmpty()) {
                addToChat(question, Message.SEND_BY_ME);
                message_text_text.setText("");
                callGeminiAPI(question);
            }
        });
    }

    void addToChat(String message, String sendBy) {
        runOnUiThread(() -> {
            Message newMessage = new Message(message, sendBy);
            messageList.add(newMessage);
            messageAdapter.notifyDataSetChanged();
            recyclerView.smoothScrollToPosition(messageAdapter.getItemCount());

            saveChatToHistory(newMessage);
        });
    }

    private void saveChatToHistory(Message message) {
        if (firebaseUser != null) {
            String userId = firebaseUser.getUid();

            // Initialize currentChatId if it's null
            if (currentChatId == null) {
                currentChatId = "chat_" + System.currentTimeMillis();
            }

            String messageKey = databaseReference.child(userId).child(currentChatId).push().getKey();

            databaseReference.child(userId).child(currentChatId).child(messageKey).setValue(message)
                    .addOnCompleteListener(task -> {
                        if (task.isSuccessful()) {
                            Log.d("Firebase", "Message saved");
                        } else {
                            Log.e("Firebase", "Message save failed", task.getException());
                        }
                    });
        }
    }

    void addResponse(String response) {
        messageList.remove(messageList.size() - 1);
        addToChat(response, Message.SEND_BY_BOT);
    }

    void callGeminiAPI(String question) {
        messageList.add(new Message("Typing...", Message.SEND_BY_BOT));

        JSONObject part = new JSONObject();
        JSONObject roleObj = new JSONObject();
        JSONArray partsArray = new JSONArray();
        JSONObject contentObj = new JSONObject();
        JSONArray contentsArray = new JSONArray();

        try {
            part.put("text", question);
            partsArray.put(part);

            roleObj.put("role", "user");
            roleObj.put("parts", partsArray);
            contentsArray.put(roleObj);

            contentObj.put("contents", contentsArray);

        } catch (JSONException e) {
            e.printStackTrace();
            addResponse("Failed to build request: " + e.getMessage());
            return;
        }

        RequestBody requestBody = RequestBody.create(contentObj.toString(), JSON);
        Request request = new Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-pro-latest:generateContent?key=" + API.API)
                .header("Content-Type", "application/json")
                .post(requestBody)
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                addResponse("Failed to load response due to: " + e.getMessage());
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try {
                    if (response.isSuccessful()) {
                        JSONObject jsonObject = new JSONObject(response.body().string());
                        JSONArray candidates = jsonObject.getJSONArray("candidates");
                        if (candidates.length() > 0) {
                            JSONObject content = candidates.getJSONObject(0).getJSONObject("content");
                            JSONArray parts = content.getJSONArray("parts");
                            if (parts.length() > 0) {
                                String result = parts.getJSONObject(0).getString("text");
                                addResponse(result.trim());
                            } else {
                                addResponse("No parts found in Gemini response.");
                            }
                        } else {
                            addResponse("No candidates found in Gemini response.");
                        }
                    } else {
                        String errorMessage = response.body().string();
                        Log.d("API_RESPONSE", errorMessage);
                        addResponse("Error from Gemini API: " + errorMessage);
                    }
                } catch (JSONException e) {
                    e.printStackTrace();
                    addResponse("Failed to parse response: " + e.getMessage());
                }
            }
        });
    }
}
