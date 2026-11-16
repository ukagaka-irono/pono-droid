#include "jni_common.h"
#include "libkawari/kawari.h"
#include "misc/i18n.h"
#include <cstring>
#include <memory>
#include <sstream>

namespace {
// Upstream's POSIX entry point is a singleton. Keep independent dictionaries and locales here.
struct Instance {
    TNS_KawariANI engine;
    std::string locale;
};
std::map<long, std::unique_ptr<Instance>> instances;
long nextId = 0;
long Load(char* input, long length) {
    std::unique_ptr<char, decltype(&free)> owned(input, free);
    std::string dir(input, static_cast<size_t>(length));
    lang.setLocale("ja_JP.SJIS");
    std::unique_ptr<Instance> instance(new Instance);
    if (!instance->engine.Load(dir)) return -1;
    instance->locale = lang.getLocale();
    const long id = nextId++;
    instances.emplace(id, std::move(instance));
    return id;
}
char* Request(long id, char* input, long* length) {
    std::unique_ptr<char, decltype(&free)> owned(input, free);
    auto it = instances.find(id);
    if (it == instances.end()) return nullptr;
    std::string raw(input, static_cast<size_t>(*length));
    std::istringstream stream(raw);
    std::string line;
    if (!std::getline(stream, line)) return nullptr;
    const auto marker = line.find(" SHIORI/2.");
    if (marker == std::string::npos || marker == 0) return nullptr;
    const auto method = line.substr(0, marker);
    TMMap<std::string, std::string> headers;
    headers.Add("ORIGINAL", raw);
    while (std::getline(stream, line)) {
        if (!line.empty() && line.back() == '\r') line.pop_back();
        if (line.empty()) break;
        const auto colon = line.find(':');
        if (colon == std::string::npos || colon == 0) continue;
        const auto start = line.find_first_not_of(" \t", colon + 1);
        headers.Add(line.substr(0, colon), start == std::string::npos ? "" : line.substr(start));
    }
    lang.setLocale(it->second->locale);
    const auto response = it->second->engine.Request(method, headers);
    it->second->locale = lang.getLocale();
    char* output = static_cast<char*>(malloc(response.size() + 1));
    if (!output) return nullptr;
    memcpy(output, response.c_str(), response.size() + 1);
    *length = static_cast<long>(response.size());
    return output;
}
bool Unload(long id) {
    auto it = instances.find(id);
    if (it == instances.end()) return false;
    lang.setLocale(it->second->locale);
    const bool result = it->second->engine.Unload();
    instances.erase(it);
    return result;
}
const pono::ShioriEngine engine = {Load, Request, Unload, true};
}
PONO_DEFINE_SHIORI_JNI(Kawari7Native, engine)
