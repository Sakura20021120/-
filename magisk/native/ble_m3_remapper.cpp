#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <sys/ioctl.h>
#include <unistd.h>

#include <array>
#include <chrono>
#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <thread>
#include <vector>

namespace {
constexpr char kDeviceName[] = "BLE-M3";
constexpr char kVirtualName[] = "BLE-M3 Remapper";
constexpr char kConfigPath[] = "/data/adb/ble-m3-remapper.conf";
constexpr char kStatePath[] = "/data/adb/ble-m3-remapper.state";
constexpr int kDefaultX = 1012;
constexpr int kDefaultY = 1740;
constexpr int kTargetX = 900;
constexpr int kTargetY = 3370;
constexpr int kTolerance = 5;

struct Config {
  int default_x = kDefaultX;
  int default_y = kDefaultY;
  int target_x = kTargetX;
  int target_y = kTargetY;
  int tolerance = kTolerance;
};

void Log(const char* format, ...) {
  va_list args;
  va_start(args, format);
  std::vfprintf(stderr, format, args);
  std::fputc('\n', stderr);
  va_end(args);
}

void LoadConfig(Config* config) {
  FILE* file = std::fopen(kConfigPath, "r");
  if (file == nullptr) return;
  char line[128];
  while (std::fgets(line, sizeof(line), file) != nullptr) {
    char key[64] = {};
    int value = 0;
    if (std::sscanf(line, "%63[^=]=%d", key, &value) != 2) continue;
    if (std::strcmp(key, "default_x") == 0) config->default_x = value;
    if (std::strcmp(key, "default_y") == 0) config->default_y = value;
    if (std::strcmp(key, "target_x") == 0) config->target_x = value;
    if (std::strcmp(key, "target_y") == 0) config->target_y = value;
    if (std::strcmp(key, "tolerance") == 0) config->tolerance = value;
  }
  std::fclose(file);
}

void WriteState(int x, int y, bool touch_down) {
  FILE* file = std::fopen(kStatePath, "w");
  if (file == nullptr) return;
  std::fprintf(file, "last_x=%d\nlast_y=%d\ntouch_down=%d\n", x, y, touch_down ? 1 : 0);
  std::fclose(file);
}

bool IsSet(const unsigned long* bits, int bit) {
  return (bits[bit / (8 * sizeof(unsigned long))] &
          (1UL << (bit % (8 * sizeof(unsigned long))))) != 0;
}

std::string FindBleM3Device() {
  DIR* directory = opendir("/dev/input");
  if (directory == nullptr) return {};
  std::string result;
  while (dirent* entry = readdir(directory)) {
    if (std::strncmp(entry->d_name, "event", 5) != 0) continue;
    const std::string path = std::string("/dev/input/") + entry->d_name;
    const int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) continue;
    char name[128] = {};
    const bool matches = ioctl(fd, EVIOCGNAME(sizeof(name)), name) >= 0 &&
                         std::strcmp(name, kDeviceName) == 0;
    close(fd);
    if (matches) {
      result = path;
      break;
    }
  }
  closedir(directory);
  return result;
}

int OpenUinput() {
  int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK | O_CLOEXEC);
  if (fd < 0) fd = open("/dev/input/uinput", O_WRONLY | O_NONBLOCK | O_CLOEXEC);
  return fd;
}

bool EnableCapabilities(int source, int uinput) {
  std::array<unsigned long, (KEY_MAX / (8 * sizeof(unsigned long))) + 2> bits{};
  for (int type = 0; type <= EV_MAX; ++type) {
    if (ioctl(source, EVIOCGBIT(0, sizeof(bits)), bits.data()) < 0 || !IsSet(bits.data(), type)) {
      continue;
    }
    if (ioctl(uinput, UI_SET_EVBIT, type) < 0) return false;
  }

  struct Capability { int type; unsigned long request; unsigned long setup; int max; };
  const Capability capabilities[] = {
      {EV_KEY, EVIOCGBIT(EV_KEY, sizeof(bits)), UI_SET_KEYBIT, KEY_MAX},
      {EV_REL, EVIOCGBIT(EV_REL, sizeof(bits)), UI_SET_RELBIT, REL_MAX},
      {EV_ABS, EVIOCGBIT(EV_ABS, sizeof(bits)), UI_SET_ABSBIT, ABS_MAX},
      {EV_MSC, EVIOCGBIT(EV_MSC, sizeof(bits)), UI_SET_MSCBIT, MSC_MAX},
      {EV_SW, EVIOCGBIT(EV_SW, sizeof(bits)), UI_SET_SWBIT, SW_MAX},
      {EV_LED, EVIOCGBIT(EV_LED, sizeof(bits)), UI_SET_LEDBIT, LED_MAX},
      {EV_SND, EVIOCGBIT(EV_SND, sizeof(bits)), UI_SET_SNDBIT, SND_MAX},
      {EV_FF, EVIOCGBIT(EV_FF, sizeof(bits)), UI_SET_FFBIT, FF_MAX},
  };
  for (const auto& capability : capabilities) {
    bits.fill(0);
    if (ioctl(source, capability.request, bits.data()) < 0) continue;
    for (int code = 0; code <= capability.max; ++code) {
      if (IsSet(bits.data(), code) && ioctl(uinput, capability.setup, code) < 0) return false;
    }
  }
  return true;
}

bool SetupAbsAxes(int source, int uinput) {
  std::array<unsigned long, (ABS_MAX / (8 * sizeof(unsigned long))) + 2> bits{};
  if (ioctl(source, EVIOCGBIT(EV_ABS, sizeof(bits)), bits.data()) < 0) return true;
  for (int axis = 0; axis <= ABS_MAX; ++axis) {
    if (!IsSet(bits.data(), axis)) continue;
    input_absinfo absinfo{};
    if (ioctl(source, EVIOCGABS(axis), &absinfo) < 0) return false;
    uinput_abs_setup setup{};
    setup.code = axis;
    setup.absinfo = absinfo;
    if (ioctl(uinput, UI_ABS_SETUP, &setup) < 0) return false;
  }
  return true;
}

int CreateVirtualDevice(int source) {
  const int uinput = OpenUinput();
  if (uinput < 0) return -1;
  if (!EnableCapabilities(source, uinput) || !SetupAbsAxes(source, uinput)) {
    close(uinput);
    return -1;
  }
  input_id id{};
  ioctl(source, EVIOCGID, &id);
  uinput_setup setup{};
  std::snprintf(setup.name, sizeof(setup.name), "%s", kVirtualName);
  setup.id = id;
  setup.id.bustype = BUS_VIRTUAL;
  if (ioctl(uinput, UI_DEV_SETUP, &setup) < 0 || ioctl(uinput, UI_DEV_CREATE) < 0) {
    close(uinput);
    return -1;
  }
  return uinput;
}

void DestroyVirtualDevice(int uinput) {
  if (uinput >= 0) {
    ioctl(uinput, UI_DEV_DESTROY);
    close(uinput);
  }
}

bool WriteEvents(int fd, const std::vector<input_event>& events) {
  const char* data = reinterpret_cast<const char*>(events.data());
  size_t remaining = events.size() * sizeof(input_event);
  while (remaining > 0) {
    const ssize_t written = write(fd, data, remaining);
    if (written <= 0) return false;
    data += written;
    remaining -= static_cast<size_t>(written);
  }
  return true;
}

bool IsCenterClick(int x, int y, const Config& config) {
  return std::abs(x - config.default_x) <= config.tolerance &&
         std::abs(y - config.default_y) <= config.tolerance;
}

void RemapBatch(std::vector<input_event>* batch, bool touch_down, int x, int y,
                const Config& config) {
  if (!touch_down || !IsCenterClick(x, y, config)) return;
  for (auto& event : *batch) {
    if (event.type != EV_ABS) continue;
    if (event.code == ABS_X) event.value = config.target_x;
    if (event.code == ABS_Y) event.value = config.target_y;
  }
  Log("BLE-M3: redirected (%d,%d) -> (%d,%d)", x, y, config.target_x, config.target_y);
}

void ProcessDevice(const std::string& path) {
  const int source = open(path.c_str(), O_RDONLY | O_CLOEXEC);
  if (source < 0) return;
  const int uinput = CreateVirtualDevice(source);
  if (uinput < 0) {
    Log("BLE-M3: cannot create uinput device: %s", std::strerror(errno));
    close(source);
    return;
  }
  if (ioctl(source, EVIOCGRAB, 1) < 0) {
    Log("BLE-M3: cannot grab %s: %s", path.c_str(), std::strerror(errno));
    DestroyVirtualDevice(uinput);
    close(source);
    return;
  }
  Log("BLE-M3: remapping %s", path.c_str());

  Config config;
  LoadConfig(&config);
  bool touch_down = false;
  int x = -1;
  int y = -1;
  std::vector<input_event> batch;
  input_event event{};
  while (read(source, &event, sizeof(event)) == sizeof(event)) {
    batch.push_back(event);
    if (event.type == EV_KEY && event.code == BTN_TOUCH) touch_down = event.value != 0;
    if (event.type == EV_ABS && event.code == ABS_X) x = event.value;
    if (event.type == EV_ABS && event.code == ABS_Y) y = event.value;
    if (event.type != EV_SYN || event.code != SYN_REPORT) continue;

    // The companion app updates this small file through root. Reloading at report
    // boundaries makes a changed center target take effect without a reboot.
    config = Config{};
    LoadConfig(&config);
    WriteState(x, y, touch_down);
    RemapBatch(&batch, touch_down, x, y, config);
    if (!WriteEvents(uinput, batch)) break;
    batch.clear();
  }
  ioctl(source, EVIOCGRAB, 0);
  DestroyVirtualDevice(uinput);
  close(source);
  Log("BLE-M3: input device disconnected, waiting to reconnect");
}
}  // namespace

int main() {
  Log("BLE-M3 Magisk remapper started");
  while (true) {
    const std::string device = FindBleM3Device();
    if (device.empty()) {
      std::this_thread::sleep_for(std::chrono::seconds(2));
      continue;
    }
    ProcessDevice(device);
    std::this_thread::sleep_for(std::chrono::seconds(1));
  }
}
