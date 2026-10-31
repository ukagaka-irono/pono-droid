// csaori の csaori_base.h の代わり（Android 用）。クラスの形は元と同じで、Windows の型（HGLOBAL・HANDLE）と DllMain は持たない。
// SAORI の入口（load / request / unload）は shim/pono_saori_guest.cpp にあり、shim/pono_csaori.cpp がこのクラスにつなぐ
#pragma once

#include <map>
#include <sstream>
#include <string>
#include <vector>

#include "csaori_util.h"

typedef enum {
    SAORIRESULT_FORCE_OK = 0,
    SAORIRESULT_OK = 200,
    SAORIRESULT_NO_CONTENT = 204,
    SAORIRESULT_BAD_REQUEST = 400,
    SAORIRESULT_INTERNAL_SERVER_ERROR = 500
} SAORIRESULT;

class CSAORIBase;

typedef std::map<string_t, string_t> map_strpair;
typedef std::vector<string_t> vector_str;

class CSAORIInput {
private:
    const CSAORIBase &base;
    CSAORIInput(void);

public:
    CSAORIInput(const CSAORIBase &b) : base(b) {}
    unsigned int codepage;
    string_t cmd;
    string_t id;
    vector_str args;
    map_strpair opts;
    bool parseString(const string_t &src);
};

class CSAORIOutput {
private:
    const CSAORIBase &base;
    CSAORIOutput(void);

public:
    CSAORIOutput(const CSAORIBase &b) : base(b) {}
    unsigned int codepage;
    SAORIRESULT result_code;
    string_t result;
    vector_str values;
    map_strpair opts;
    string_t toString();
    void setResultEmpty();
};

class CSAORIBase {
public:
    CSAORIBase() : call_id(0) {}
    virtual ~CSAORIBase() {}

private:
    string_t module_path;
    unsigned int call_id;

public:
    void setModulePath(const std::string &str, bool isUTF8);
    const string_t &getModulePath(void) const { return module_path; }
    std::string request(const std::string &req);
    std::string checkAndModifyPath(const std::string &path);
    string_t checkAndModifyPathW(const string_t &path);
    unsigned int getLastCallID() { return call_id; }

    virtual const string_t &s_saori_version(void) const = 0;
    virtual const string_t &s_saori_def(void) const = 0;
    virtual const string_t &s_saori_argument(void) const = 0;
    virtual const string_t &s_saori_value(void) const = 0;
    virtual const string_t &s_saori_result(void) const = 0;

    virtual void exec_before(const CSAORIInput &, CSAORIOutput &) {}
    virtual void exec_after(const CSAORIInput &, CSAORIOutput &) {}
    virtual void exec(const CSAORIInput &in, CSAORIOutput &out) = 0;
    virtual bool exec_insecure(const CSAORIInput &, CSAORIOutput &) { return false; }
    virtual bool unload() = 0;
    virtual bool load() = 0;
};
