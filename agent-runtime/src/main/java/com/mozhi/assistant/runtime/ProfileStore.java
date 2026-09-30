package com.mozhi.assistant.runtime;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.mozhi.assistant.runtime.model.UserProfile;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * UserProfile 的 JSON 存储。
 * 不缓存画像：每次修改都在文件锁内重读，并在验证成功后原子替换。
 */
public final class ProfileStore {
    private static final int MAX_PROFILE_BYTES = 32 * 1024;
    private static final int MAX_SECTION_JSON_CHARACTERS = 32 * 1024;
    private static final int MAX_FACTS = 100;
    private static final int MAX_FACT_CHARACTERS = 1500;
    private static final int MAX_CATEGORY_CHARACTERS = 80;
    private static final int MAX_FACT_ID_CHARACTERS = 100;

    private static final TypeReference<Map<String, Double>> INTERESTS_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<String>> CONSTRAINTS_TYPE = new TypeReference<>() {};

    private final Path file;
    private final ObjectMapper mapper;

    public ProfileStore(Path file) {
        this.file = file.toAbsolutePath().normalize();
        mapper = createMapper();
    }

    public Path path() {
        return file;
    }

    public UserProfile read() {
        if (!Files.exists(file)) {
            return emptyProfile();
        }
        try {
            if (Files.size(file) > MAX_PROFILE_BYTES) {
                throw new IOException("画像超过 32 KiB 上限");
            }
            UserProfile profile = mapper.readValue(Files.readAllBytes(file), UserProfile.class);
            validateProfile(profile);
            return profile;
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("无法读取长期记忆 JSON，原文件未被覆盖：" + file, exception);
        }
    }

    public String json() {
        UserProfile profile = read();
        try {
            return mapper.writeValueAsString(profile);
        } catch (IOException exception) {
            throw new IllegalStateException("无法编码用户画像", exception);
        }
    }

    public String remember(String content, String category) {
        validateFactInput(content, category);
        String factId = UUID.randomUUID().toString();
        update(profile -> {
            if (profile.getFacts().size() >= MAX_FACTS) {
                throw new IllegalArgumentException("最多保存 100 条事实，请先整理记忆");
            }
            Instant now = Instant.now();
            UserProfile.Fact fact = UserProfile.Fact.builder()
                    .id(factId)
                    .content(content)
                    .category(category)
                    .confidence(1.0)
                    .source("user_stated")
                    .createdAt(now)
                    .updatedAt(now)
                    .build();
            profile.getFacts().add(fact);
        });
        return factId;
    }

    public void edit(String factId, String content, String category) {
        validateFactInput(content, category);
        update(profile -> {
            UserProfile.Fact fact = findFact(profile, factId);
            fact.setContent(content)
                    .setCategory(category)
                    .setUpdatedAt(Instant.now());
            // 自由文本摘要可能仍含旧事实，不能继续沿用。
            profile.setNarrative(null);
        });
    }

    public void forget(String factId) {
        update(profile -> {
            boolean removed = profile.getFacts().removeIf(fact -> fact.getId().equals(factId));
            if (!removed) {
                throw new IllegalArgumentException("记忆 ID 不存在");
            }
            profile.setNarrative(null);
        });
    }

    /** 完整替换一个画像分区；JSON null 表示清空该分区。 */
    public void replaceSection(String section, String json) {
        requireText(json, MAX_SECTION_JSON_CHARACTERS, "画像 JSON");
        update(profile -> {
            try {
                JsonNode value = mapper.readTree(json);
                if (value == null) {
                    throw new IllegalArgumentException("画像 JSON 不能为空");
                }
                applySection(profile, section, value);
                if (!section.equals("narrative")) {
                    profile.setNarrative(null);
                }
            } catch (IOException exception) {
                throw new IllegalArgumentException("画像 JSON 格式错误", exception);
            }
        });
    }

    public void clear() {
        update(profile -> profile
                .setBasic(null)
                .setPreferences(null)
                .setInterests(new HashMap<>())
                .setFacts(new ArrayList<>())
                .setConstraints(new ArrayList<>())
                .setNarrative(null)
                .setStats(null));
    }

    private void applySection(UserProfile profile, String section, JsonNode value) throws IOException {
        switch (section) {
            case "basic" -> profile.setBasic(mapper.treeToValue(value, UserProfile.BasicInfo.class));
            case "preferences" -> profile.setPreferences(
                    mapper.treeToValue(value, UserProfile.Preferences.class));
            case "interests" -> profile.setInterests(
                    value.isNull() ? new HashMap<>() : mapper.convertValue(value, INTERESTS_TYPE));
            case "constraints" -> profile.setConstraints(
                    value.isNull() ? new ArrayList<>() : mapper.convertValue(value, CONSTRAINTS_TYPE));
            case "narrative" -> profile.setNarrative(readNarrative(value));
            default -> throw new IllegalArgumentException(
                    "支持的画像分区：basic/preferences/interests/constraints/narrative");
        }
    }

    private static String readNarrative(JsonNode value) {
        if (!value.isNull() && !value.isTextual()) {
            throw new IllegalArgumentException("narrative 必须是字符串或 null");
        }
        return value.isNull() ? null : value.textValue();
    }

    /** 所有写操作都经过此事务入口，业务方法不直接写文件。 */
    private void update(Consumer<UserProfile> mutation) {
        if (Thread.currentThread().isInterrupted()) {
            throw new IllegalStateException("请求已取消，未保存记忆");
        }
        try {
            Files.createDirectories(file.getParent());
            Path lockPath = file.resolveSibling(file.getFileName() + ".lock");
            try (FileChannel channel = FileChannel.open(
                    lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock lock = channel.tryLock()) {
                if (lock == null) {
                    throw new IOException("另一会话正在写入画像，请重试");
                }
                UserProfile profile = read();
                mutation.accept(profile);
                profile.setUpdatedAt(Instant.now());
                writeAtomically(serializeForStorage(profile));
            }
        } catch (IOException | OverlappingFileLockException exception) {
            throw new IllegalStateException("长期记忆未保存：" + exception.getMessage(), exception);
        }
    }

    private byte[] serializeForStorage(UserProfile profile) throws IOException {
        validateProfile(profile);
        byte[] bytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(profile);
        if (bytes.length > MAX_PROFILE_BYTES) {
            throw new IllegalArgumentException("长期记忆超过 32 KiB，请先遗忘不再需要的内容");
        }
        return bytes;
    }

    private void writeAtomically(byte[] bytes) throws IOException {
        Path temporaryFile = Files.createTempFile(file.getParent(), "profile-", ".tmp");
        try {
            Files.write(temporaryFile, bytes);
            if (Thread.currentThread().isInterrupted()) {
                throw new IOException("请求已取消");
            }
            // 不支持原子替换时直接报错，避免原文件只写入一半。
            Files.move(temporaryFile, file,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporaryFile);
        }
    }

    private static UserProfile emptyProfile() {
        Instant now = Instant.now();
        return UserProfile.builder().createdAt(now).updatedAt(now).build();
    }

    private static UserProfile.Fact findFact(UserProfile profile, String factId) {
        return profile.getFacts().stream()
                .filter(fact -> fact.getId().equals(factId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("记忆 ID 不存在"));
    }

    private static void validateFactInput(String content, String category) {
        requireText(content, MAX_FACT_CHARACTERS, "记忆内容");
        requireText(category, MAX_CATEGORY_CHARACTERS, "记忆分类");
    }

    private static void validateProfile(UserProfile profile) {
        if (profile == null || profile.getFacts() == null
                || profile.getInterests() == null || profile.getConstraints() == null) {
            throw new IllegalArgumentException("画像及 facts/interests/constraints 不可为 null");
        }
        if (profile.getFacts().size() > MAX_FACTS) {
            throw new IllegalArgumentException("画像事实超过 100 条");
        }
        validateFacts(profile.getFacts());
        for (Double weight : profile.getInterests().values()) {
            if (weight == null || !Double.isFinite(weight)) {
                throw new IllegalArgumentException("兴趣权重必须是有限数值");
            }
        }
    }

    private static void validateFacts(List<UserProfile.Fact> facts) {
        Set<String> ids = new HashSet<>();
        for (UserProfile.Fact fact : facts) {
            if (fact == null) {
                throw new IllegalArgumentException("事实不可为 null");
            }
            requireText(fact.getId(), MAX_FACT_ID_CHARACTERS, "事实 ID");
            requireText(fact.getContent(), MAX_FACT_CHARACTERS, "事实内容");
            if (!ids.add(fact.getId())) {
                throw new IllegalArgumentException("事实 ID 重复");
            }
        }
    }

    private static void requireText(String value, int limit, String label) {
        if (value == null || value.isBlank() || value.length() > limit) {
            throw new IllegalArgumentException(label + "不能为空，且不能超过 " + limit + " 个字符");
        }
    }

    private static ObjectMapper createMapper() {
        // 单独注册 Instant，使现有 JSON 时间格式保持为 ISO-8601 字符串。
        SimpleModule timeModule = new SimpleModule();
        timeModule.addSerializer(Instant.class, new JsonSerializer<Instant>() {
            @Override
            public void serialize(Instant value, JsonGenerator output, SerializerProvider provider)
                    throws IOException {
                output.writeString(value.toString());
            }
        });
        timeModule.addDeserializer(Instant.class, new JsonDeserializer<Instant>() {
            @Override
            public Instant deserialize(JsonParser input, DeserializationContext context) throws IOException {
                return Instant.parse(input.getValueAsString());
            }
        });
        return new ObjectMapper()
                .registerModule(timeModule)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }
}
