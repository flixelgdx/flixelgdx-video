import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

interface VlcNativesExtension {
  val vlcVersion: Property<String>
  val platformDir: Property<String>
  val downloads: ListProperty<Map<String, String>>
  val excludeSdk: Property<Boolean>
}
