// Field Twin Companion v1
// Dashboard screen for Field Twin on the Waveshare ESP32-C6-LCD-1.47 (ST7789, 172 x 320, portrait).
// It pairs with the Field Twin phone app over Bluetooth Low Energy as "FT Companion", shows the same view as the
// floating map window (next turn, distance, route, your position), reads its own GPS so the phone can leave its GPS
// off, and measures road roughness with an accelerometer. Everything is controlled from the phone; no buttons needed.
//
// Add-on wiring (3.3 volt parts):
//   GPS module (NMEA, 9600 baud): GPS TX to GPIO17, GPS RX to GPIO16, VCC to 3V3, GND to GND
//   MPU6050 accelerometer board (GY-521): SDA to GPIO2, SCL to GPIO3, VCC to 3V3, GND to GND

#include <Arduino_GFX_Library.h>
#include <TinyGPSPlus.h>
#include <Wire.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include <math.h>

#define FTC_VERSION "v1"

// Board pins, from the Waveshare ESP32-C6-LCD-1.47 schematic
#define PIN_LCD_MOSI 6
#define PIN_LCD_SCK 7
#define PIN_LCD_CS 14
#define PIN_LCD_DC 15
#define PIN_LCD_RST 21
#define PIN_LCD_BL 22

// Add-on pins
#define PIN_GPS_RX 17
#define PIN_GPS_TX 16
#define GPS_BAUD 9600
#define PIN_I2C_SDA 2
#define PIN_I2C_SCL 3
#define MPU_ADDR 0x68

// Bluetooth names
#define BLE_NAME "FT Companion"
#define UUID_SERVICE "b2f0a001-6c1e-4f2a-9d4b-667477000001"
#define UUID_NAV "b2f0a001-6c1e-4f2a-9d4b-667477000002"
#define UUID_POS "b2f0a001-6c1e-4f2a-9d4b-667477000003"
#define UUID_ROAD "b2f0a001-6c1e-4f2a-9d4b-667477000004"

// Colors, light theme with Field Twin blue
#define C_WHITE 0xFFFF
#define C_INK 0x08C5
#define C_BLUE 0x0A74
#define C_PALE 0xEFBF
#define C_GREEN 0x2CE9
#define C_ORANGE 0xE3C0
#define C_RED 0xC145
#define C_GREY 0x8CB5
#define C_LINE 0xCE79

#define W 172
#define H 320

Arduino_DataBus *bus = new Arduino_ESP32SPI(PIN_LCD_DC, PIN_LCD_CS, PIN_LCD_SCK, PIN_LCD_MOSI, GFX_NOT_DEFINED);
Arduino_GFX *panel = new Arduino_ST7789(bus, PIN_LCD_RST, 0, true, W, H, 34, 0, 34, 0);
Arduino_Canvas *gfx = new Arduino_Canvas(W, H, panel);

TinyGPSPlus gps;
HardwareSerial GPSSerial(1);

// ---------- state ----------
struct Step { float lat; float lon; char text[64]; };
#define MAX_ROUTE 500
#define MAX_STEPS 80
#define MAX_LOCKERS 120
float routeLat[MAX_ROUTE], routeLon[MAX_ROUTE];
int routeN = 0;
Step steps[MAX_STEPS];
int stepN = 0, stepNext = 1;
bool routeActive = false;
float lockerLat[MAX_LOCKERS], lockerLon[MAX_LOCKERS];
int lockerN = 0;

char turnText[96] = "";
float phoneDistNext = -1, phoneDistLeft = -1;
float distNext = -1, distLeft = -1;
int offCount = 0;
unsigned long lastOffSent = 0;
bool arrived = false;

bool haveFix = false;
double myLat = 0, myLon = 0;
float mySpeed = 0, myCourse = 0;
int mySats = 0;
double phoneLat = 0, phoneLon = 0;
unsigned long phonePosAt = 0;

bool imuOk = false;
float accMean = 1.0f;
double sqSum = 0;
float peak = 0;
int sampleN = 0;
float roughNow = 0;
float roughHist[60];
int roughIdx = 0;
unsigned long lastSample = 0, lastWindow = 0;

char notice[48] = "";
unsigned long noticeUntil = 0;
int brightness = 220;

bool bleLinked = false;
BLEServer *server = nullptr;
BLECharacteristic *chPos = nullptr, *chRoad = nullptr;
QueueHandle_t cmdQueue;
#define CMD_LEN 240

// ---------- helpers ----------
static float metersBetween(double aLat, double aLon, double bLat, double bLon) {
  const double k = 0.017453292519943295;
  double dLat = (bLat - aLat) * k, dLon = (bLon - aLon) * k;
  double s = sin(dLat / 2) * sin(dLat / 2) + cos(aLat * k) * cos(bLat * k) * sin(dLon / 2) * sin(dLon / 2);
  return (float)(6371000.0 * 2 * atan2(sqrt(s), sqrt(1 - s)));
}

static float metersToSegment(double pLat, double pLon, double aLat, double aLon, double bLat, double bLon) {
  double k = cos(pLat * 0.017453292519943295) * 111320.0;
  double ax = (aLon - pLon) * k, ay = (aLat - pLat) * 110540.0;
  double bx = (bLon - pLon) * k, by = (bLat - pLat) * 110540.0;
  double dx = bx - ax, dy = by - ay, len = dx * dx + dy * dy;
  double t = len > 0 ? -(ax * dx + ay * dy) / len : 0;
  if (t < 0) t = 0; if (t > 1) t = 1;
  double x = ax + t * dx, y = ay + t * dy;
  return (float)sqrt(x * x + y * y);
}

static void distText(float m, char *out, size_t n) {
  if (m < 0) { snprintf(out, n, " "); return; }
  float mi = m / 1609.34f;
  if (mi >= 0.2f) snprintf(out, n, "%.1f mi", mi);
  else snprintf(out, n, "%d ft", (int)(roundf(m * 3.28084f / 50.0f) * 50));
}

static void showNotice(const char *t, unsigned long ms) {
  strncpy(notice, t, sizeof(notice) - 1);
  notice[sizeof(notice) - 1] = 0;
  noticeUntil = millis() + ms;
}

static bool hasPosition() { return haveFix || (phonePosAt && millis() - phonePosAt < 30000); }
static double posLat() { return haveFix ? myLat : phoneLat; }
static double posLon() { return haveFix ? myLon : phoneLon; }

static void notify(BLECharacteristic *ch, const char *msg) {
  if (!bleLinked || !ch) return;
  ch->setValue((uint8_t *)msg, strlen(msg));
  ch->notify();
}

// ---------- Bluetooth ----------
class ServerCB : public BLEServerCallbacks {
  void onConnect(BLEServer *s) override { bleLinked = true; showNotice("phone linked", 3000); }
  void onDisconnect(BLEServer *s) override { bleLinked = false; showNotice("phone link lost", 4000); BLEDevice::startAdvertising(); }
};

// Each write is one command. The Bluetooth task only queues it; the main loop does the work.
class NavCB : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *c) override {
    String v = c->getValue();
    char buf[CMD_LEN];
    size_t n = v.length() < CMD_LEN - 1 ? v.length() : CMD_LEN - 1;
    memcpy(buf, v.c_str(), n);
    buf[n] = 0;
    xQueueSend(cmdQueue, buf, 0);
  }
};

static void bleStart() {
  BLEDevice::init(BLE_NAME);
  BLEDevice::setMTU(247);
  server = BLEDevice::createServer();
  server->setCallbacks(new ServerCB());
  BLEService *svc = server->createService(UUID_SERVICE);
  BLECharacteristic *nav = svc->createCharacteristic(UUID_NAV, BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR);
  nav->setCallbacks(new NavCB());
  chPos = svc->createCharacteristic(UUID_POS, BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY);
  chPos->addDescriptor(new BLE2902());
  chRoad = svc->createCharacteristic(UUID_ROAD, BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY);
  chRoad->addDescriptor(new BLE2902());
  svc->start();
  BLEAdvertising *adv = BLEDevice::getAdvertising();
  adv->addServiceUUID(UUID_SERVICE);
  adv->setScanResponse(true);
  BLEDevice::startAdvertising();
}

// ---------- commands from the phone ----------
// R0                 clear the route
// RP|lat,lon;lat,lon add route line points
// RS|lat|lon|text    add a turn
// RG                 start following the route
// T|text             turn text from the phone
// D|meters|meters    distance to next turn and to the end, from the phone
// X                  end the route
// H|lat|lon          phone position, used when this board has no GPS fix
// L0 / L|lat|lon     clear or add locker pins
// B|0-255            screen brightness
// N|text             show a short notice
static void handleCommand(char *c) {
  if (c[0] == 'R' && c[1] == '0') { routeN = 0; stepN = 0; stepNext = 1; routeActive = false; arrived = false; turnText[0] = 0; return; }
  if (c[0] == 'R' && c[1] == 'P' && c[2] == '|') {
    char *p = c + 3;
    while (*p && routeN < MAX_ROUTE) {
      char *comma = strchr(p, ',');
      if (!comma) break;
      float la = atof(p), lo = atof(comma + 1);
      routeLat[routeN] = la; routeLon[routeN] = lo; routeN++;
      char *semi = strchr(comma, ';');
      if (!semi) break;
      p = semi + 1;
    }
    return;
  }
  if (c[0] == 'R' && c[1] == 'S' && c[2] == '|' && stepN < MAX_STEPS) {
    char *a = c + 3, *b = strchr(a, '|'); if (!b) return;
    char *t = strchr(b + 1, '|'); if (!t) return;
    steps[stepN].lat = atof(a); steps[stepN].lon = atof(b + 1);
    strncpy(steps[stepN].text, t + 1, sizeof(steps[stepN].text) - 1);
    steps[stepN].text[sizeof(steps[stepN].text) - 1] = 0;
    stepN++;
    return;
  }
  if (c[0] == 'R' && c[1] == 'G') { routeActive = true; arrived = false; stepNext = stepN > 1 ? 1 : 0; offCount = 0; showNotice("route started", 3000); return; }
  if (c[0] == 'T' && c[1] == '|') { strncpy(turnText, c + 2, sizeof(turnText) - 1); turnText[sizeof(turnText) - 1] = 0; return; }
  if (c[0] == 'D' && c[1] == '|') { phoneDistNext = atof(c + 2); char *b = strchr(c + 2, '|'); phoneDistLeft = b ? atof(b + 1) : -1; return; }
  if (c[0] == 'X') { routeActive = false; routeN = 0; stepN = 0; turnText[0] = 0; showNotice("route ended", 3000); return; }
  if (c[0] == 'H' && c[1] == '|') { phoneLat = atof(c + 2); char *b = strchr(c + 2, '|'); if (b) { phoneLon = atof(b + 1); phonePosAt = millis(); } return; }
  if (c[0] == 'L' && c[1] == '0') { lockerN = 0; return; }
  if (c[0] == 'L' && c[1] == '|' && lockerN < MAX_LOCKERS) { lockerLat[lockerN] = atof(c + 2); char *b = strchr(c + 2, '|'); if (b) { lockerLon[lockerN] = atof(b + 1); lockerN++; } return; }
  if (c[0] == 'B' && c[1] == '|') { brightness = constrain(atoi(c + 2), 10, 255); analogWrite(PIN_LCD_BL, brightness); return; }
  if (c[0] == 'N' && c[1] == '|') { showNotice(c + 2, 5000); return; }
}

// ---------- route following on the board's own GPS ----------
static void followRoute() {
  if (!routeActive || !haveFix || stepN == 0) return;
  if (stepNext >= stepN) stepNext = stepN - 1;
  float d = metersBetween(myLat, myLon, steps[stepNext].lat, steps[stepNext].lon);
  if (d < 30 && stepNext < stepN - 1) { stepNext++; d = metersBetween(myLat, myLon, steps[stepNext].lat, steps[stepNext].lon); }
  distNext = d;
  if (routeN > 1) {
    int best = 0; float bestD = 1e9;
    for (int i = 1; i < routeN; i++) {
      float s = metersToSegment(myLat, myLon, routeLat[i - 1], routeLon[i - 1], routeLat[i], routeLon[i]);
      if (s < bestD) { bestD = s; best = i; }
    }
    float left = metersBetween(myLat, myLon, routeLat[best], routeLon[best]);
    for (int i = best + 1; i < routeN; i++) left += metersBetween(routeLat[i - 1], routeLon[i - 1], routeLat[i], routeLon[i]);
    distLeft = left;
    if (bestD > 60) offCount++; else offCount = 0;
    if (offCount >= 3 && millis() - lastOffSent > 20000) {
      char m[64];
      snprintf(m, sizeof(m), "O|%.6f|%.6f", myLat, myLon);
      notify(chPos, m);
      lastOffSent = millis();
      showNotice("off route, asking phone", 4000);
    }
    float toEnd = metersBetween(myLat, myLon, routeLat[routeN - 1], routeLon[routeN - 1]);
    if (toEnd < 60 && !arrived) { arrived = true; notify(chPos, "A"); showNotice("you have arrived", 8000); }
  }
}

// ---------- accelerometer ----------
static bool mpuWrite(uint8_t reg, uint8_t val) {
  Wire.beginTransmission(MPU_ADDR); Wire.write(reg); Wire.write(val);
  return Wire.endTransmission() == 0;
}
static bool mpuRead(float &ax, float &ay, float &az) {
  Wire.beginTransmission(MPU_ADDR); Wire.write(0x3B);
  if (Wire.endTransmission(false) != 0) return false;
  if (Wire.requestFrom((uint8_t)MPU_ADDR, (uint8_t)6) != 6) return false;
  int16_t x = (Wire.read() << 8) | Wire.read();
  int16_t y = (Wire.read() << 8) | Wire.read();
  int16_t z = (Wire.read() << 8) | Wire.read();
  ax = x / 8192.0f; ay = y / 8192.0f; az = z / 8192.0f;
  return true;
}
static void imuStart() {
  Wire.begin(PIN_I2C_SDA, PIN_I2C_SCL, 400000);
  imuOk = mpuWrite(0x6B, 0x00) && mpuWrite(0x1C, 0x08) && mpuWrite(0x1A, 0x03);
  for (int i = 0; i < 60; i++) roughHist[i] = 0;
}
// Samples about two hundred times a second. Each second it turns the shaking, with gravity taken out, into one
// roughness number in thousandths of a g, and sends it to the phone with the spot it was measured.
static void imuTick() {
  if (!imuOk) return;
  unsigned long now = micros();
  if (now - lastSample < 5000) return;
  lastSample = now;
  float ax, ay, az;
  if (!mpuRead(ax, ay, az)) return;
  float mag = sqrtf(ax * ax + ay * ay + az * az);
  accMean += (mag - accMean) * 0.01f;
  float dev = mag - accMean;
  sqSum += dev * dev;
  if (fabsf(dev) > peak) peak = fabsf(dev);
  sampleN++;
  if (millis() - lastWindow >= 1000 && sampleN > 20) {
    roughNow = sqrtf(sqSum / sampleN) * 1000.0f;
    roughHist[roughIdx] = roughNow;
    roughIdx = (roughIdx + 1) % 60;
    if (haveFix && mySpeed > 2.0f) {
      char m[80];
      snprintf(m, sizeof(m), "Q|%.6f|%.6f|%d|%d|%.1f", myLat, myLon, (int)roughNow, (int)(peak * 1000), mySpeed);
      notify(chRoad, m);
    }
    sqSum = 0; peak = 0; sampleN = 0; lastWindow = millis();
  }
}

// ---------- GPS ----------
static unsigned long lastPosSent = 0;
static void gpsTick() {
  while (GPSSerial.available()) gps.encode(GPSSerial.read());
  if (gps.location.isUpdated()) {
    haveFix = gps.location.isValid() && gps.location.age() < 3000;
    myLat = gps.location.lat(); myLon = gps.location.lng();
    if (gps.speed.isValid()) mySpeed = gps.speed.mps();
    if (gps.course.isValid() && mySpeed > 1.5f) myCourse = gps.course.deg();
    followRoute();
  }
  if (gps.location.isValid() && gps.location.age() > 5000) haveFix = false;
  if (gps.satellites.isValid()) mySats = gps.satellites.value();
  if (haveFix && millis() - lastPosSent > 1000) {
    char m[96];
    snprintf(m, sizeof(m), "P|%.6f|%.6f|%.1f|%.0f|%d", myLat, myLon, mySpeed, myCourse, mySats);
    notify(chPos, m);
    lastPosSent = millis();
  }
}

// ---------- drawing ----------
static int wrapText(const char *t, int x, int y, int maxW, int size, uint16_t color, int maxLines) {
  gfx->setTextSize(size);
  gfx->setTextColor(color);
  int cw = 6 * size, lh = 8 * size + 3;
  int perLine = maxW / cw;
  char line[40];
  int lines = 0;
  const char *p = t;
  while (*p && lines < maxLines) {
    while (*p == ' ') p++;
    int len = strlen(p);
    int take = len <= perLine ? len : perLine;
    if (len > perLine) { int cut = take; while (cut > 0 && p[cut] != ' ') cut--; if (cut > 0) take = cut; }
    if (take > 39) take = 39;
    memcpy(line, p, take); line[take] = 0;
    gfx->setCursor(x, y + lines * lh);
    gfx->print(line);
    p += take;
    lines++;
  }
  return lines * lh;
}

// Draws the map north up around the position, scaled so the next turn fits on screen.
static void drawMap(int top, int bottom) {
  int mh = bottom - top;
  gfx->fillRect(0, top, W, mh, C_PALE);
  double cLat, cLon;
  if (hasPosition()) { cLat = posLat(); cLon = posLon(); }
  else if (routeN) { cLat = routeLat[0]; cLon = routeLon[0]; }
  else {
    gfx->setTextSize(1); gfx->setTextColor(C_GREY);
    gfx->setCursor(30, top + mh / 2 - 4); gfx->print("waiting for GPS");
    return;
  }
  float radius = 400;
  if (routeActive && stepN && stepNext < stepN) {
    float d = metersBetween(cLat, cLon, steps[stepNext].lat, steps[stepNext].lon);
    radius = constrain(d * 1.3f, 150.0f, 4000.0f);
  }
  float mPerPx = radius / (W / 2.0f - 6);
  double kx = cos(cLat * 0.017453292519943295) * 111320.0;
  int cx = W / 2, cy = top + mh / 2;
  auto px = [&](double la, double lo, int &x, int &y) {
    x = cx + (int)(((lo - cLon) * kx) / mPerPx);
    y = cy - (int)(((la - cLat) * 110540.0) / mPerPx);
  };
  for (int i = 0; i < lockerN; i++) {
    int x, y; px(lockerLat[i], lockerLon[i], x, y);
    if (x > 2 && x < W - 2 && y > top + 2 && y < bottom - 2) gfx->fillRect(x - 2, y - 2, 5, 5, C_ORANGE);
  }
  for (int i = 1; i < routeN; i++) {
    int x0, y0, x1, y1;
    px(routeLat[i - 1], routeLon[i - 1], x0, y0);
    px(routeLat[i], routeLon[i], x1, y1);
    if ((x0 < -200 && x1 < -200) || (x0 > W + 200 && x1 > W + 200)) continue;
    for (int o = -1; o <= 1; o++) { gfx->drawLine(x0 + o, y0, x1 + o, y1, C_BLUE); gfx->drawLine(x0, y0 + o, x1, y1 + o, C_BLUE); }
  }
  if (routeActive && stepN && stepNext < stepN) {
    int x, y; px(steps[stepNext].lat, steps[stepNext].lon, x, y);
    gfx->fillCircle(x, y, 5, C_WHITE); gfx->drawCircle(x, y, 5, C_INK);
  }
  if (routeN > 1) {
    int x, y; px(routeLat[routeN - 1], routeLon[routeN - 1], x, y);
    gfx->fillCircle(x, y, 6, C_GREEN); gfx->drawCircle(x, y, 6, C_INK);
  }
  if (hasPosition()) {
    float a = myCourse * 0.017453292519943295f;
    int x0 = cx + (int)(sinf(a) * 11), y0 = cy - (int)(cosf(a) * 11);
    int x1 = cx + (int)(sinf(a + 2.5f) * 8), y1 = cy - (int)(cosf(a + 2.5f) * 8);
    int x2 = cx + (int)(sinf(a - 2.5f) * 8), y2 = cy - (int)(cosf(a - 2.5f) * 8);
    gfx->fillTriangle(x0, y0, x1, y1, x2, y2, C_INK);
    gfx->drawTriangle(x0, y0, x1, y1, x2, y2, C_WHITE);
  }
  gfx->drawFastHLine(0, top, W, C_LINE);
  gfx->drawFastHLine(0, bottom - 1, W, C_LINE);
}

static void drawScreen() {
  gfx->fillScreen(C_WHITE);
  char a[24], b[24];
  int headBottom = 104;
  if (routeActive || turnText[0]) {
    const char *t = turnText;
    if (haveFix && routeActive && stepN && stepNext < stepN) t = steps[stepNext].text;
    float dn = (haveFix && distNext >= 0) ? distNext : phoneDistNext;
    float dl = (haveFix && distLeft >= 0) ? distLeft : phoneDistLeft;
    distText(dn, a, sizeof(a));
    gfx->setTextSize(3); gfx->setTextColor(C_BLUE); gfx->setCursor(6, 6); gfx->print(a);
    wrapText(t[0] ? t : "Getting a route", 6, 36, W - 10, 2, C_INK, 3);
    distText(dl, b, sizeof(b));
    gfx->setTextSize(1); gfx->setTextColor(C_GREY); gfx->setCursor(6, 94); gfx->print(b); if (dl >= 0) gfx->print(" to go");
  } else {
    gfx->setTextSize(2); gfx->setTextColor(C_INK); gfx->setCursor(6, 8); gfx->print("FIELD TWIN");
    gfx->setTextColor(C_BLUE); gfx->setCursor(6, 30); gfx->print("COMPANION");
    gfx->setTextSize(4); gfx->setTextColor(C_INK); gfx->setCursor(6, 58);
    gfx->print((int)roundf(mySpeed * 2.23694f));
    gfx->setTextSize(1); gfx->setTextColor(C_GREY); gfx->print(" mph");
  }
  drawMap(headBottom, 272);

  // status strip
  gfx->setTextSize(1);
  gfx->setCursor(4, 278);
  gfx->setTextColor(haveFix ? C_GREEN : C_RED);
  if (haveFix) { gfx->print("GPS "); gfx->print(mySats); } else gfx->print(gps.charsProcessed() > 10 ? "GPS no fix" : "GPS none");
  gfx->setCursor(78, 278);
  gfx->setTextColor(bleLinked ? C_BLUE : C_GREY);
  gfx->print(bleLinked ? "linked" : "no phone");
  gfx->setCursor(130, 278);
  gfx->setTextColor(imuOk ? C_INK : C_GREY);
  gfx->print(imuOk ? "road" : "no imu");

  // roughness history bars, newest on the right
  int bx = 4, by = 314, bh = 24;
  for (int i = 0; i < 60; i++) {
    float r = roughHist[(roughIdx + i) % 60];
    int h = (int)constrain(r / 6.0f, 0.0f, (float)bh);
    uint16_t col = r < 60 ? C_GREEN : (r < 140 ? C_ORANGE : C_RED);
    if (h > 0) gfx->fillRect(bx + i * 2 + i / 2, by - h, 2, h, col);
  }
  gfx->drawFastHLine(4, by + 1, 164, C_LINE);

  if (noticeUntil > millis()) {
    gfx->fillRoundRect(6, 240, W - 12, 26, 6, C_BLUE);
    gfx->setTextSize(1); gfx->setTextColor(C_WHITE);
    gfx->setCursor(12, 249); gfx->print(notice);
  }
  gfx->flush();
}

// ---------- main ----------
void setup() {
  Serial.begin(115200);
  pinMode(PIN_LCD_BL, OUTPUT);
  analogWrite(PIN_LCD_BL, brightness);
  gfx->begin(40000000);
  gfx->fillScreen(C_WHITE);
  gfx->setTextSize(2); gfx->setTextColor(C_BLUE); gfx->setCursor(6, 140); gfx->print("FT Companion");
  gfx->setTextSize(1); gfx->setTextColor(C_GREY); gfx->setCursor(6, 164); gfx->print("firmware " FTC_VERSION);
  gfx->flush();
  cmdQueue = xQueueCreate(48, CMD_LEN);
  GPSSerial.begin(GPS_BAUD, SERIAL_8N1, PIN_GPS_RX, PIN_GPS_TX);
  imuStart();
  bleStart();
  Serial.printf("FT Companion %s ready, imu %s\n", FTC_VERSION, imuOk ? "found" : "missing");
}

unsigned long lastDraw = 0;
void loop() {
  char c[CMD_LEN];
  while (xQueueReceive(cmdQueue, c, 0) == pdTRUE) handleCommand(c);
  gpsTick();
  imuTick();
  if (millis() - lastDraw > 200) { drawScreen(); lastDraw = millis(); }
  delay(1);
}
