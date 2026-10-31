package org.example.harness;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Configuration
@ConfigurationProperties(prefix = "app.chat")
@Validated
public class ChatProperties {

    /** * Максимальное количество "ходов" (пар User+Assistant) в истории одного диалога. */
    @Min(1) private int maxHistoryTurns = 20;

    /** * Максимальное количество одновременно хранимых в памяти диалогов. */
    @Min(1) private int maxConversations = 1000;

    /** * Время жизни неактивного диалога в кэше. */
    @NotNull
    private Duration conversationTtl = Duration.ofMinutes(60);

    /** * Базовые параметры модели Ollama по умолчанию. */
    @NotBlank
    private String defaultModel = "qwen3.5:4b";

    @Min(1)
    private int defaultNumPredict = 512;

    private double defaultTemperature = 0.2;

    /** * Системный промпт, задающий поведение модели. */
    private String defaultSystemPrompt = "";

    /** * Часовой пояс для генерации временного контекста. */
    @NotBlank private String timezone = "Europe/Moscow";

    public int getMaxHistoryTurns() {
        return maxHistoryTurns;
    }

    public void setMaxHistoryTurns(int maxHistoryTurns) {
        this.maxHistoryTurns = maxHistoryTurns;
    }

    public int getMaxConversations() {
        return maxConversations;
    }

    public void setMaxConversations(int maxConversations) {
        this.maxConversations = maxConversations;
    }

    public Duration getConversationTtl() {
        return conversationTtl;
    }

    public void setConversationTtl(Duration conversationTtl) {
        this.conversationTtl = conversationTtl;
    }

    public String getDefaultModel() {
        return defaultModel;
    }

    public void setDefaultModel(String defaultModel) {
        this.defaultModel = defaultModel;
    }

    public int getDefaultNumPredict() {
        return defaultNumPredict;
    }

    public void setDefaultNumPredict(int defaultNumPredict) {
        this.defaultNumPredict = defaultNumPredict;
    }

    public double getDefaultTemperature() {
        return defaultTemperature;
    }

    public void setDefaultTemperature(double defaultTemperature) {
        this.defaultTemperature = defaultTemperature;
    }

    public String getDefaultSystemPrompt() {
        return defaultSystemPrompt;
    }

    public void setDefaultSystemPrompt(String defaultSystemPrompt) {
        this.defaultSystemPrompt = defaultSystemPrompt;
    }

    public String getTimezone() {
        return timezone;
    }

    public void setTimezone(String timezone) {
        this.timezone = timezone;
    }
}
