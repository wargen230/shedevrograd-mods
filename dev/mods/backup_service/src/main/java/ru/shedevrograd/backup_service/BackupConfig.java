package ru.shedevrograd.backup_service;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;

/** config/backup_service-common.toml */
public final class BackupConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    static {
        BUILDER.push("schedule");
    }

    public static final ModConfigSpec.BooleanValue SCHEDULE_ENABLED = BUILDER
            .comment("Делать бэкапы автоматически по расписанию")
            .define("enabled", true);

    public static final ModConfigSpec.ConfigValue<String> TIME = BUILDER
            .comment("Время ежедневного бэкапа, ЧЧ:ММ, по часовому поясу сервера")
            .define("time", "03:00", value -> value instanceof String s && BackupConfig.parseTime(s) != null);

    public static final ModConfigSpec.EnumValue<DayOfWeek> FULL_BACKUP_DAY = BUILDER
            .comment("День недели для полного бэкапа. В остальные дни делается дифференциальный (только изменения с последнего полного)")
            .defineEnum("fullBackupDay", DayOfWeek.SUNDAY);

    static {
        BUILDER.pop().push("retention");
    }

    public static final ModConfigSpec.IntValue DAILY_DAYS = BUILDER
            .comment("Хранить все бэкапы за столько последних дней")
            .defineInRange("dailyDays", 7, 1, 365);

    public static final ModConfigSpec.IntValue WEEKLY_COUNT = BUILDER
            .comment("Хранить столько последних полных бэкапов (кроме тех, что попали в dailyDays)")
            .defineInRange("weeklyCount", 4, 1, 100);

    static {
        BUILDER.pop().push("storage");
    }

    public static final ModConfigSpec.ConfigValue<String> DIRECTORY = BUILDER
            .comment("Папка для бэкапов, относительно папки сервера или абсолютный путь")
            .define("directory", "backups");

    public static final ModConfigSpec.IntValue COMPRESSION_LEVEL = BUILDER
            .comment("Уровень сжатия LZMA2: 0 — быстрее, 9 — меньше файл, но заметно дольше и больше памяти")
            .defineInRange("compressionLevel", 3, 0, 9);

    static {
        BUILDER.pop().push("s3");
    }

    public static final ModConfigSpec.BooleanValue S3_ENABLED = BUILDER
            .comment("Дублировать бэкапы в S3-хранилище. Локальные бэкапы остаются как есть")
            .define("enabled", false);

    public static final ModConfigSpec.ConfigValue<String> S3_ENDPOINT = BUILDER
            .comment("Адрес S3 API, например http://host:port или https://s3.example.com")
            .define("endpoint", "http://185.246.118.82:7070");

    public static final ModConfigSpec.ConfigValue<String> S3_REGION = BUILDER
            .comment("Регион. Для MinIO, Versity Gateway и большинства своих хранилищ — us-east-1")
            .define("region", "us-east-1");

    public static final ModConfigSpec.ConfigValue<String> S3_BUCKET = BUILDER
            .comment("Бакет, должен уже существовать")
            .define("bucket", "shedevrograd-backups");

    public static final ModConfigSpec.ConfigValue<String> S3_PREFIX = BUILDER
            .comment("Папка внутри бакета, например \"survival\". Пусто — в корень бакета")
            .define("prefix", "");

    public static final ModConfigSpec.ConfigValue<String> S3_ACCESS_KEY = BUILDER
            .comment("Access key. Не коммитьте этот файл с заполненными ключами")
            .define("accessKey", "");

    public static final ModConfigSpec.ConfigValue<String> S3_SECRET_KEY = BUILDER
            .comment("Secret key")
            .define("secretKey", "");

    public static final ModConfigSpec.BooleanValue S3_PATH_STYLE = BUILDER
            .comment("true: http://host/бакет/файл (MinIO, Versity). false: http://бакет.host/файл")
            .define("pathStyle", true);

    static {
        BUILDER.pop();
    }

    public static final ModConfigSpec SPEC = BUILDER.build();

    private BackupConfig() {}

    public static LocalTime time() {
        return BackupConfig.parseTime(TIME.get());
    }

    public static Path directory() {
        return Path.of(DIRECTORY.get()).toAbsolutePath().normalize();
    }

    private static LocalTime parseTime(String value) {
        try {
            return LocalTime.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
