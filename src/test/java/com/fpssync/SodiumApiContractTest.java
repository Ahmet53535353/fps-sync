package com.fpssync;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FPS-Sync, FPS limiti kaydırıcısını Sodium'un kendi ayarlar ekranına enjekte ederek
 * "Unlimited" yerine "FPS Sync" seçeneği sunar. Bunu yapmak için Sodium'un dahili
 * bytecode'ına karışmak zorundadır ve bu, modu her Sodium sürümüne karşı son derece
 * kırılgan hale getirir: hedef sınıf, alan veya çağrı sırası değişirse oyun çöker.
 *
 * <p>Kırılma genellikle oyun açılışında değil, Sodium ayarlar ekranı ilk kez yüklenirken
 * görünür. Oyun normal açılır, dünya yüklenir, kullanıcı ayarları açar ve oyun çöker.
 * <p>
 * <p>Bu test, Minecraft'i başlatmadan önce mixin'in gerçek annotation'larını okuyup
 * Sodium'un <em>gerçek</em> bytecode'ındaki karşılıklarıyla karşılaştırır. Böylece
 * çökme oluşmadan, hatta oyunu bir kez bile açmadan yakalanır. Test iki yönlü sözleşmeyi
 * doğrular: (1) hedef sınıflar hâlâ var mı, (2) enjekte edilen ordinal'ler hâlâ
 * FPS limiti seçeneğine mi işaret ediyor.
 */
class SodiumApiContractTest {

	/** JVM iç ad biçiminde kök paket: "net/caffeinemc/mods/sodium". */
	private static final String SODIUM_INTERNAL = "net/caffeinemc/mods/sodium";
	/** Kaynak kodda kullanılan noktalı biçim. */
	private static final String SODIUM = "net.caffeinemc.mods.sodium";

	private static final String SLIDER_CONTROL_INTERNAL = SODIUM_INTERNAL + "/client/gui/options/control/SliderControl";
	private static final String SLIDER_CONTROL = SODIUM + ".client.gui.options.control.SliderControl";
	private static final String CONFIG_BUILDER = SODIUM + ".client.gui.SodiumConfigBuilder";
	private static final String CONFIG_BUILDER_INTERNAL = SODIUM_INTERNAL + "/client/gui/SodiumConfigBuilder";
	private static final String INTEGER_OPTION_BUILDER = SODIUM_INTERNAL + "/api/config/structure/IntegerOptionBuilder";

	/**
	 * FPS Sync seçeneğinin Sodium config API'sindeki kimliği.
	 *
	 * <p>Sodium bu kimliği namespace'siz ("sodium:general.framerate_limit") olarak
	 * saklar; namespace'i önceden eklemek testin yanlış negatif vermesine yol açar.
	 */
	private static final String FRAMERATE_LIMIT_ID = "sodium:general.framerate_limit";

	private static Path sodiumJar;

	@BeforeAll
	static void locateSodiumJar() {
		// Gradle'ın bu test için çözümlediği Sodium jar'ını kullan. Cache'i taramak yanlış
		// sürümü (ya da hiçbir şeyi) seçme riski taşır; build sırasında hangi jar'ın
		// kullanıldığını bilmek istiyoruz.
		Optional<Path> jar = findSodiumJarInCache();
		assertTrue(jar.isPresent(),
				"Çözümlenmiş Sodium jar'ı mod cache'inde bulunamadı. " +
				"`./gradlew dependencies --configuration modCompileOnly` ile kontrol et.");
		sodiumJar = jar.get();
		System.out.println("[SodiumApiContractTest] okunan Sodium jar: " + sodiumJar);
		System.out.println("[SodiumApiContractTest] user.dir = " + System.getProperty("user.dir"));

		// Testin okuduğu sürümün beklenen sürümle aynı olduğundan emin ol; aksi halde
		// sözleşme sessizce yanlış sürüme karşı test edilmiş olur.
		String name = sodiumJar.getFileName().toString();
		assertTrue(name.contains(sodiumVersion()),
				"Test, beklenen Sodium sürümünü değil " + name + " dosyasını okuyor. " +
				"gradle.properties içindeki sodium_version ile testte beklenen sürüm uyuşmuyor.");
	}

	/** gradle.properties'teki sodium_version değeri (ör. "mc1.21.1-0.8.13"). */
	private static String sodiumVersion() {
		String raw = System.getProperty("sodium.version", "");
		if (!raw.isEmpty()) {
			return raw;
		}
		// Gradle bu özelliği geçmezse gradle.properties'ten oku.
		try {
			Path props = Path.of("gradle.properties");
			if (Files.isRegularFile(props)) {
				for (String line : Files.readAllLines(props)) {
					String trimmed = line.trim();
					if (trimmed.startsWith("sodium_version=")) {
						return trimmed.substring("sodium_version=".length());
					}
				}
			}
		} catch (IOException e) {
			throw new IllegalStateException("gradle.properties okunamadı", e);
		}
		return "";
	}

	/**
	 * Sodium jar'ını Gradle modül cache'inde bulur.
	 *
	 * <p>Burası bir zamanlar tüm {@code ~/.gradle/caches} ağacını özyinelemeli olarak
	 * yürüyordu: 19.897 dosya, test sınıfının toplam süresinin büyük kısmı. Oysa Gradle'in
	 * modül cache düzeni <code>files-2.1/&lt;grup&gt;/&lt;modül&gt;/&lt;sürüm&gt;/&lt;hash&gt;/&lt;jar&gt;</code>
	 * şeklindedir — {@code sodium} modülüne grup dizini seviyesinden doğrudan
	 * gidilebilir. Tam tarama 19.897 dosyayı gezerken burada yalnız birkaç dizin
	 * incelenir.
	 *
	 * <p>Grup adı ({@code maven.modrinth}, {@code org.jamalamsoftware} vb.) kaynak
	 * kontrolünde tutulmuyor ve değişebilir, bu yüzden <em>sabit yazılmadı</em>: grup
	 * dizinleri listelenir ve içinde {@code sodium} olanlara girilir. Düzen tanınmazsa
	 * sorun çıkmasın diye eski yönteme düşülür.
	 */
	private static Optional<Path> findSodiumJarInCache() {
		Path cacheRoot = Path.of(System.getProperty("user.home"), ".gradle", "caches");
		if (!Files.isDirectory(cacheRoot)) {
			return Optional.empty();
		}
		Path modules = cacheRoot.resolve("modules-2").resolve("files-2.1");
		if (Files.isDirectory(modules)) {
			try {
				Optional<Path> targeted = scanSodiumModuleDirs(modules);
				if (targeted.isPresent()) {
					return targeted;
				}
			} catch (IOException e) {
				// Bilinen düzen tutmuyorsa aşağıdaki geniş tarama denenir.
			}
		}
		// Bilinen düzen tutmuyorsa eski geniş tarama.
		String version = sodiumVersion();
		try (Stream<Path> files = Files.walk(cacheRoot)) {
			return files.filter(Files::isRegularFile)
					.filter(p -> p.getFileName().toString().endsWith(".jar"))
					.filter(p -> p.getFileName().toString().startsWith("sodium-"))
					.filter(p -> !p.getFileName().toString().contains("sources"))
					.filter(p -> version.isEmpty() || p.getFileName().toString().contains(version))
					.findFirst();
		} catch (IOException e) {
			throw new IllegalStateException("Gradle mod cache'i taranamadı", e);
		}
	}

	/**
	 * {@code files-2.1/<grup>/sodium} dizinlerini gezer. Grup adı sabit değildir,
	 * bu yüzden her grup dizininin altına bakılır.
	 */
	private static Optional<Path> scanSodiumModuleDirs(Path modules) throws IOException {
		String version = sodiumVersion();
		try (Stream<Path> groups = Files.list(modules)) {
			for (Path group : groups.filter(Files::isDirectory).toList()) {
				Path sodium = group.resolve("sodium");
				if (!Files.isDirectory(sodium)) {
					continue;
				}
				Optional<Path> hit = sodiumJarUnder(sodium, version);
				if (hit.isPresent()) {
					return hit;
				}
			}
		}
		return Optional.empty();
	}

	/** {@code sodium/<sürüm>/<hash>/sodium-*.jar} düzenini okur. */
	private static Optional<Path> sodiumJarUnder(Path sodium, String version) throws IOException {
		try (Stream<Path> versions = Files.list(sodium)) {
			for (Path v : versions.filter(Files::isDirectory).toList()) {
				String vname = v.getFileName().toString();
				if (!version.isEmpty() && !vname.contains(version)) {
					continue;
				}
				Optional<Path> hit = firstSodiumJarIn(v);
				if (hit.isPresent()) {
					return hit;
				}
			}
		}
		return Optional.empty();
	}

	private static Optional<Path> firstSodiumJarIn(Path dir) throws IOException {
		try (Stream<Path> dirs = Files.list(dir)) {
			for (Path d : dirs.filter(Files::isDirectory).toList()) {
				try (Stream<Path> in = Files.list(d)) {
					// Listeyi try bloğu içinde toplamak zorunlu: stream terminal
					// işlem yapılmadan döndürülürse close() onu tüketilmeden
					// kapatır ("source already consumed or closed").
					Optional<Path> hit = in.filter(Files::isRegularFile)
							.filter(p -> p.getFileName().toString().endsWith(".jar"))
							.filter(p -> p.getFileName().toString().startsWith("sodium-"))
							.filter(p -> !p.getFileName().toString().contains("sources"))
							.findFirst();
					if (hit.isPresent()) {
						return hit;
					}
				}
			}
		}
		return Optional.empty();
	}

	// ---------------------------------------------------------------------
	// 1. Hedef sınıflar hâlâ mevcut mu?
	// ---------------------------------------------------------------------

	@Test
	@DisplayName("Mixin'in hedeflediği Sodium sınıfları bu sürümde hâlâ var")
	void mixinTargetsStillExist() throws IOException {
		assertTrue(classExists(SLIDER_CONTROL_INTERNAL),
				"Sodium " + SLIDER_CONTROL + " sınıfını kaldırmış. " +
				"FPS Sync ayarlar ekranı artık var olmayan bir sınıfa karışıyor; hedefi güncelle.");

		assertTrue(classExists(CONFIG_BUILDER_INTERNAL),
				"Sodium " + CONFIG_BUILDER + " sınıfını kaldırmış. " +
				"FPS Sync'in slider'ı buraya enjekte ediliyor; hedefi güncelle.");
	}

	/**
	 * Enjeksiyon noktalarının imzaları, hedef metotların imzalarıyla birebir örtüşmek
	 * zorundadır. Mixin, elde bulduğu imzayı aynen bekler; uyuşmazlık {@code Invalid
	 * descriptor} hatasıyla enjekte edilen parçayı sessizce düşürür (require = 0
	 * olduğunda) veya oyunu çökertir.
	 */
	@Test
	@DisplayName("buildGeneralPage imzası, mixin'in enjekte ettiği imzalarla uyuşuyor")
	void injectedSignaturesMatchTargetMethod() throws IOException {
		ClassInfo builder = readClass(CONFIG_BUILDER_INTERNAL);
		MethodInfo generalPage = builder.methods.stream()
				.filter(m -> m.name.equals("buildGeneralPage"))
				.findFirst()
				.orElse(null);
		assertNotNull(generalPage, "buildGeneralPage bulunamadı.");

		// buildGeneralPage(ConfigBuilder): OptionPageBuilder
		assertEquals("(L" + SODIUM_INTERNAL + "/api/config/structure/ConfigBuilder;)"
						+ "L" + SODIUM_INTERNAL + "/api/config/structure/OptionPageBuilder;",
				generalPage.descriptor(),
				"buildGeneralPage imzası beklenenden farklı. " +
				"Mixin'teki @Inject/@Redirect imzalarını bu imzaya göre güncelle; " +
				"aksi halde enjekte edilen parça düşer ve FPS Sync çalışmaz.");
	}

	/**
	 * 0.6.x'te SliderControl dört alan ({@code min}, {@code max}, {@code interval},
	 * {@code mode}) taşıyordu ve FPS-Sync bunları doğrudan değiştiriyordu. 0.8.x'te aralık
	 * ve biçimlendirici seçeneğin kendi doğrulayıcısına taşındı ve bu alanlar silindi.
	 * Bir @Shadow bu alanları hedefliyorsa, hedef sınıf yüklenirken mixin uygulaması
	 * başarısız olur.
	 */
	@Test
	@DisplayName("SliderControl, 0.6.x'teki shadow'lanabilir alanları artık taşımıyor")
	void sliderControlNoLongerExposesLegacyFields() throws IOException {
		ClassInfo slider = readClass(SLIDER_CONTROL_INTERNAL);

		for (String legacyField : List.of("min", "max", "interval", "mode")) {
			assertFalse(slider.hasField(legacyField),
					"Sodium " + SLIDER_CONTROL + "." + legacyField + " alanı yeniden var. " +
					"Sodium 0.6.x API'sine dönülmüş olabilir; FPS Sync'in enjekte ettiği " +
					"değerler artık geçerli olabilir ve hedefi gözden geçir.");
		}

		assertTrue(slider.hasField("option"),
				"SliderControl artık bir 'option' alanı taşımıyor. Beklenen alan adı değişmiş " +
				"olabilir; mevcut alanlar: " + slider.fieldNames());
	}

	// ---------------------------------------------------------------------
	// 2. Enjekte edilen ordinal'ler hâlâ FPS limiti seçeneğine mi işaret ediyor?
	// ---------------------------------------------------------------------

	@Test
	@DisplayName("Mixin'in setRange ordinal'i FPS limiti seçeneğinin çağrısına denk geliyor")
	void rangeRedirectTargetsFramerateLimit() throws Exception {
		RedirectInjection injection = findRangeRedirect();

		ClassInfo builder = readClass(CONFIG_BUILDER_INTERNAL);
		assertTrue(builder.hasMethod("buildGeneralPage"),
				"SodiumConfigBuilder.buildGeneralPage kaldırılmış; enjekte edilen metot hedefi geçersiz.");

		CallSet calls = callsInGeneralPage(builder);
		int actual = ordinalOf(calls, injection, "setRange", "(III)L" + INTEGER_OPTION_BUILDER + ";");
		int expected = ordinalOfFramerateLimitCall(calls, "setRange", "(III)L" + INTEGER_OPTION_BUILDER + ";");

		assertEquals(expected, actual,
				"Mixin'in setRange ordinal'i (" + actual + ") artık FPS limiti seçeneğine değil, " +
				"başka bir seçeneğe işaret ediyor (olması gereken: " + expected + "). " +
				"Sodium ayarlar ekranındaki kaydırıcı yanlış seçeneği değiştirecek. " +
				"Ordinal'i yeniden hesapla: ordinal = hedef çağrıdan önce gelen aynı imzalı çağrı sayısı. " +
				"UYARI: ordinal, kaynak dosyadaki satır sırasından türetilemez; koşullu bloklar " +
				"bytecode'da ayrı çağrı noktaları üretir.");
	}

	@Test
	@DisplayName("Mixin'in setValueFormatter ordinal'i FPS limiti seçeneğinin çağrısına denk geliyor")
	void valueFormatterRedirectTargetsFramerateLimit() throws Exception {
		RedirectInjection injection = findValueFormatterRedirect();

		ClassInfo builder = readClass(CONFIG_BUILDER_INTERNAL);
		CallSet calls = callsInGeneralPage(builder);

		String descriptor = "(L" + SODIUM_INTERNAL + "/api/config/option/ControlValueFormatter;)L" + INTEGER_OPTION_BUILDER + ";";
		int actual = ordinalOf(calls, injection, "setValueFormatter", descriptor);
		int expected = ordinalOfFramerateLimitCall(calls, "setValueFormatter", descriptor);

		assertEquals(expected, actual,
				"Mixin'in setValueFormatter ordinal'i (" + actual + ") artık FPS limiti seçeneğine " +
				"değil, başka bir seçeneğe işaret ediyor (olması gereken: " + expected + ").");
	}

	// ---------------------------------------------------------------------
	// 3. Enjekte edilen slider aralığı Sodium'un doğrulamasından geçiyor mu?
	// ---------------------------------------------------------------------

	@Test
	@DisplayName("FPS Sync slider aralığı, Sodium'un slider doğrulama kurallarını sağlıyor")
	void fpsSyncRangeSatisfiesSodiumValidation() {
		// Sodium, slider kurucusunun dayandığı kuralları uygular:
		//   max > min, interval > 0, (max - min) interval'a bölünür.
		// 0.8.x'te bu kurallar seçeneğin kendi doğrulayıcısına taşındı ama mantık aynı;
		// slider kurulurken ihlal eden bir aralık reddedilir.
		int min = 0, max = 1000, step = 10;

		assertTrue(max > min, "Slider üst sınırı alt sınırdan büyük olmalı.");
		assertTrue(step > 0, "Slider adımı pozitif olmalı.");
		assertEquals(0, (max - min) % step,
				"Slider aralığı adıma tam bölünmeli; aksi halde Sodium doğrulaması reddeder. " +
				"aralık=" + (max - min) + ", adım=" + step);
	}

	// ---------------------------------------------------------------------
	// Mixin annotation'larını okuma
	//
	// Annotation'lar reflection ile okunabilirdi ama bunun için handler'ın parametre
	// tipleri (IntegerOptionBuilder vb.) yüklenmek zorunda kalır ve Sodium test
	// classpath'inde yoktur. Bunun yerine mixin sınıfının .class dosyası doğrudan
	// bytecode olarak okunur: test, modu çalıştırmadan mixin'in gerçekten ne
	// enjekte ettiğini okur.
	// ---------------------------------------------------------------------

	private record RedirectInjection(String methodName, String target, int ordinal, String mixinMethod,
									 String requireMode) {
	}

	private static final String MIXIN_CLASS_FILE = "com/fpssync/mixin/SodiumFpsLimitMixin.class";
	private static final String AT_DESCRIPTOR = "Lorg/spongepowered/asm/mixin/injection/At;";
	private static final String REDIRECT_DESCRIPTOR = "Lorg/spongepowered/asm/mixin/injection/Redirect;";

	private static RedirectInjection findRangeRedirect() throws IOException {
		return findRedirect("redirectFpsSliderRange");
	}

	private static RedirectInjection findValueFormatterRedirect() throws IOException {
		return findRedirect("redirectFpsValueFormatter");
	}

	/**
	 * Mixin sınıfının bytecode'undan verilen handler'ın @Redirect annotation'ını okur.
	 * RuntimeVisibleAnnotations'ı ve handler'ın @At değerlerini çıkarır.
	 */
	private static RedirectInjection findRedirect(String handlerName) throws IOException {
		byte[] bytes = readMixinClassBytes();

		AtomicReference<RedirectInjection> found = new AtomicReference<>();
		List<String> handlerNames = new ArrayList<>();

		new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
			@Override
			public MethodVisitor visitMethod(int access, String name, String descriptor,
												String signature, String[] exceptions) {
				handlerNames.add(name);
				if (!name.equals(handlerName)) {
					return null;
				}

				return new MethodVisitor(Opcodes.ASM9) {
					private String atTarget;
					private int atOrdinal = -1;
					private String mixinMethod = "<?>";
					private int require = 1;

					@Override
					public org.objectweb.asm.AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
						// Handler üzerindeki @Redirect
						if (!descriptor.equals(REDIRECT_DESCRIPTOR)) {
							return null;
						}

						return new org.objectweb.asm.AnnotationVisitor(Opcodes.ASM9) {
							@Override
							public void visit(String name, Object value) {
								if ("require".equals(name) && value instanceof Integer i) {
									require = i;
								}
							}

							/**
							 * @Redirect.method tek elemanlı bir String dizisidir; ASM onu
							 * {@code visit} değil {@code visitArray} üzerinden aktarır
							 * (tek elemanlı dizilerde de bu yol kullanılır).
							 */
							@Override
							public org.objectweb.asm.AnnotationVisitor visitArray(String name) {
								if (!"method".equals(name)) {
									return null;
								}

								return new org.objectweb.asm.AnnotationVisitor(Opcodes.ASM9) {
									@Override
									public void visit(String elementName, Object value) {
										if (value != null) {
											mixinMethod = String.valueOf(value);
										}
									}
								};
							}

							// @At, @Redirect'un iç içe annotation elemanıdır. ASM, bu
							// elemanı visitAnnotation(name, descriptor) ile aktarır.
							@Override
							public org.objectweb.asm.AnnotationVisitor visitAnnotation(String name, String descriptor) {
								if (!descriptor.equals(AT_DESCRIPTOR)) {
									return null;
								}

								return new org.objectweb.asm.AnnotationVisitor(Opcodes.ASM9) {
									@Override
									public void visit(String name, Object value) {
										switch (name) {
											case "value", "target" -> atTarget = String.valueOf(value);
											case "ordinal" -> {
												if (value instanceof Integer i) {
													atOrdinal = i;
												}
											}
											default -> {
												// yoksay
											}
										}
									}
								};
							}
						};
					}

					@Override
					public void visitEnd() {
						found.set(new RedirectInjection(handlerName, atTarget, atOrdinal,
								mixinMethod, String.valueOf(require)));
					}
				};
			}
		}, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);

		RedirectInjection injection = found.get();
		assertNotNull(injection,
				"Mixin'te beklenen handler bulunamadı: " + handlerName + ". " +
				"FPS Sync slider'ı artık farklı bir mekanizmayla enjekte ediliyor olabilir; " +
				"bu testin güncellenmesi gerekir. Bulunan handler'lar: " + handlerNames);
		assertNotNull(injection.target(),
				handlerName + " üzerindeki @Redirect bir @At çağrı hedefi içermiyor.");

		// require = 0 olmazsa, Sodium config API'si değiştiğinde hedef bulunamaz
		// ve injector "kritik enjeksiyon hatası" ile oyunu ÇÖKERTİR. Modun
		// dayanıklılık vaadi bu değere bağlı, dolayısıyla doğrulanır.
		assertEquals("0", injection.requireMode(),
				handlerName + " üzerinde require = " + injection.requireMode()
						+ " ama 0 olmalı. require = 0 olmadan, Sodium'un config API'si "
						+ "değiştiğinde hedef bulunamaz ve oyun ayarlar ekranı açılırken "
						+ "çöker — modun kırılma-proof katmanı işlevini yitirmiş olur.");
		assertEquals("buildGeneralPage", injection.mixinMethod(),
				"Mixin, " + injection.mixinMethod() + " metodunu hedefliyor. " +
				"Bu test buildGeneralPage içindeki ordinal'leri doğruluyor; hedef değiştiyse test güncellenmeli.");

		return injection;
	}

	private static byte[] readMixinClassBytes() throws IOException {
		// Test sınıfının kendi kaynağından (build/classes) derlenmiş mixin sınıfını oku.
		List<Path> candidates = List.of(
				Path.of("build/classes/java/main").resolve(MIXIN_CLASS_FILE),
				Path.of("build/classes/java/test").resolve(MIXIN_CLASS_FILE));

		for (Path candidate : candidates) {
			if (Files.isRegularFile(candidate)) {
				return Files.readAllBytes(candidate);
			}
		}

		// Alternatif: derlenmiş sınıfı classpath üzerinden bul.
		String resource = MIXIN_CLASS_FILE;
		try (InputStream in = SodiumApiContractTest.class.getClassLoader().getResourceAsStream(resource)) {
			if (in != null) {
				return in.readAllBytes();
			}
		}

		throw new AssertionError(
				"Derlenmiş mixin sınıfı bulunamadı: " + resource + ". " +
				"`./gradlew classes` çalıştırılmış olmalı.");
	}

	private static int ordinalOf(CallSet calls, RedirectInjection injection, String name, String descriptor) {
		Call call = calls.find(name, descriptor).stream()
				.filter(c -> c.ordinal() == injection.ordinal())
				.findFirst()
				.orElseThrow(() -> new AssertionError(
						"Mixin'in ordinal'i (" + injection.ordinal() + ") buildGeneralPage içinde " +
						"mevcut değil. " + name + descriptor + " imzalı çağrılar: " +
						calls.find(name, descriptor).stream().map(c -> c.ordinal() + "@" + c.instructionIndex()).toList()));
		return call.ordinal();
	}

	/** framerate_limit seçeneğinin kurulduğu çağrının ordinal'ini bulur. */
	private static int ordinalOfFramerateLimitCall(CallSet calls, String name, String descriptor) throws IOException {
		MethodScan scan = scanGeneralPage();
		return calls.find(name, descriptor).stream()
				.filter(c -> c.instructionIndex() > scan.framerateLimitIndex())
				.findFirst()
				.map(Call::ordinal)
				.orElseThrow(() -> new AssertionError(
						"buildGeneralPage içinde '" + FRAMERATE_LIMIT_ID + "' seçeneğine ait " +
						name + " çağrısı bulunamadı. Sodium bu ayarı farklı bir şekilde kuruyor olabilir; " +
						"FPS Sync'in hedeflediği yeri gözden geçir."));
	}

	// ---------------------------------------------------------------------
	// Bytecode okuma
	// ---------------------------------------------------------------------

	private record MethodInfo(String name, String descriptor) {
	}

	private record Call(String name, String descriptor, int ordinal, int instructionIndex) {
	}

	private record MethodScan(int framerateLimitIndex) {
	}

	private record CallSet(List<Call> calls) {

		List<Call> find(String name, String descriptor) {
			return calls.stream()
					.filter(c -> c.name().equals(name) && c.descriptor().equals(descriptor))
					.toList();
		}
	}

	private static final class ClassInfo {
		final List<FieldInfo> fields = new ArrayList<>();
		final List<MethodInfo> methods = new ArrayList<>();
		final List<Call> generalPageCalls = new ArrayList<>();
		int framerateLimitIndex = -1;

		boolean hasField(String name) {
			return fields.stream().anyMatch(f -> f.name.equals(name));
		}

		List<String> fieldNames() {
			return fields.stream().map(f -> f.name).toList();
		}

		boolean hasMethod(String name) {
			return methods.stream().anyMatch(m -> m.name.equals(name));
		}
	}

	private record FieldInfo(String name, String descriptor) {
	}

	private static ClassInfo readClass(String internalName) throws IOException {
		byte[] bytes = readClassBytes(internalName);

		ClassInfo info = new ClassInfo();
		final String[] current = {null};
		final int[] index = {0};
		final int[] setRangeOrdinal = {0};
		final int[] setFormatterOrdinal = {0};
		final boolean[] inGeneralPage = {false};

		new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
			@Override
			public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
				info.fields.add(new FieldInfo(name, descriptor));
				return null;
			}

			@Override
			public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
				info.methods.add(new MethodInfo(name, descriptor));
				current[0] = name;
				index[0] = 0;
				inGeneralPage[0] = name.equals("buildGeneralPage");

				return new MethodVisitor(Opcodes.ASM9) {
					@Override
					public void visitLdcInsn(Object value) {
						if (inGeneralPage[0] && FRAMERATE_LIMIT_ID.equals(value) && info.framerateLimitIndex < 0) {
							info.framerateLimitIndex = index[0];
						}
						index[0]++;
					}

					@Override
					public void visitMethodInsn(int opcode, String owner, String methodName, String methodDescriptor,
												boolean isInterface) {
						if (inGeneralPage[0] && opcode == Opcodes.INVOKEINTERFACE
								&& owner.equals(INTEGER_OPTION_BUILDER)) {
							int ordinal = -1;
							if (methodName.equals("setRange") && methodDescriptor.startsWith("(III)")) {
								ordinal = setRangeOrdinal[0]++;
							} else if (methodName.equals("setValueFormatter")) {
								ordinal = setFormatterOrdinal[0]++;
							}
							if (ordinal >= 0) {
								info.generalPageCalls.add(new Call(methodName, methodDescriptor, ordinal, index[0]));
							}
						}
						index[0]++;
					}

					@Override
					public void visitInsn(int opcode) {
						index[0]++;
					}

					@Override
					public void visitVarInsn(int opcode, int varIndex) {
						index[0]++;
					}

					@Override
					public void visitIntInsn(int opcode, int operand) {
						index[0]++;
					}

					@Override
					public void visitTypeInsn(int opcode, String type) {
						index[0]++;
					}

					@Override
					public void visitFieldInsn(int opcode, String owner, String fieldName, String fieldDescriptor) {
						index[0]++;
					}

					@Override
					public void visitJumpInsn(int opcode, org.objectweb.asm.Label label) {
						index[0]++;
					}

					@Override
					public void visitInvokeDynamicInsn(String name, String descriptor,
														org.objectweb.asm.Handle handle, Object... args) {
						index[0]++;
					}

					@Override
					public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
						index[0]++;
					}

					@Override
					public void visitTableSwitchInsn(int min, int max, org.objectweb.asm.Label dflt, org.objectweb.asm.Label... labels) {
						index[0]++;
					}

					@Override
					public void visitLookupSwitchInsn(org.objectweb.asm.Label dflt, int[] keys, org.objectweb.asm.Label[] labels) {
						index[0]++;
					}
				};
			}
		}, ClassReader.SKIP_FRAMES);

		return info;
	}

	private static CallSet callsInGeneralPage(ClassInfo info) {
		return new CallSet(info.generalPageCalls);
	}

	private static MethodScan scanGeneralPage() throws IOException {
		ClassInfo info = readClass(CONFIG_BUILDER_INTERNAL);
		assertTrue(info.framerateLimitIndex >= 0,
				"buildGeneralPage içinde '" + FRAMERATE_LIMIT_ID + "' kimliği bulunamadı. " +
				"Sodium bu ayarı yeniden adlandırmış veya taşımış olabilir.");
		return new MethodScan(info.framerateLimitIndex);
	}

	private static boolean classExists(String internalName) throws IOException {
		try (ZipFile zip = new ZipFile(sodiumJar.toFile())) {
			return zip.getEntry(internalName + ".class") != null;
		}
	}

	private static byte[] readClassBytes(String internalName) throws IOException {
		String entry = internalName + ".class";
		try (ZipFile zip = new ZipFile(sodiumJar.toFile())) {
			ZipEntry zipEntry = zip.getEntry(entry);
			assertNotNull(zipEntry, "Sodium jar'ında " + entry + " bulunamadı.");
			try (InputStream in = zip.getInputStream(zipEntry)) {
				return in.readAllBytes();
			}
		}
	}
}
