package org.example.reviewer;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.document.parser.TextDocumentParser;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.googleai.GoogleAiEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;

public class AgentFactory {

    // The AI Interface
    public interface CodeReviewer {
        @UserMessage("Analyze this code and return JSON: {{code}}")
        CodeAnalysis analyze(@V("code") String code);
    }

    public static CodeReviewer createReviewer(String googleKey, String groqKey, String standards) {

        // 1. Setup RAG (Using Google Embeddings)
        String embeddingModelName = ConfigLoader.get("GOOGLE_EMBEDDING_MODEL", "google.embedding.model",
                "gemini-embedding-001");
        EmbeddingModel embeddingModel = GoogleAiEmbeddingModel.builder()
                .apiKey(googleKey)
                .modelName(embeddingModelName)
                .timeout(Duration.ofSeconds(60))
                .build();

        EmbeddingStore<TextSegment> embeddingStore = new InMemoryEmbeddingStore<>();

        // 2. Ingest Rules
        try {
            Path path = Paths.get(standards);
            if (Files.exists(path)) {
                System.out.println("[RAG] Ingesting Company Standards...");
                Document doc = FileSystemDocumentLoader.loadDocument(
                        path, new TextDocumentParser());

                EmbeddingStoreIngestor.builder()
                        .embeddingModel(embeddingModel)
                        .embeddingStore(embeddingStore)
                        .build()
                        .ingest(doc);
            }
        } catch (Exception e) {
            System.err.println("[RAG] Warning: Could not load standards: " + e.getMessage());
        }

        ContentRetriever retriever = EmbeddingStoreContentRetriever.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(embeddingModel)
                .maxResults(2)
                .minScore(0.5)
                .build();

        // 3. Setup Chat Model (Using Groq)
        String chatModelName = ConfigLoader.get("GROQ_MODEL", "groq.chat.model", "llama-3.3-70b-versatile");
        System.out.println("[AI] Connecting to Groq (" + chatModelName + ")...");
        ChatLanguageModel chatModel = OpenAiChatModel.builder()
                .baseUrl("https://api.groq.com/openai/v1")
                .apiKey(groqKey)
                .modelName(chatModelName)
                .maxRetries(1)
                .timeout(Duration.ofSeconds(90))
                .build();

        // 4. Build Service
        return AiServices.builder(CodeReviewer.class)
                .chatLanguageModel(chatModel)
                .contentRetriever(retriever)
                .build();
    }
}