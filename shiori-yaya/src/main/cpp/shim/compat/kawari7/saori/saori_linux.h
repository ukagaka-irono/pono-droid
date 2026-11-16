// Android binding for the SAORI interface used by Kawari 7. Keep this header C++98 compatible.
#pragma once
#include <string>

class TSaoriBindingLnx {
public:
    static TSaoriBindingLnx* Bind(const std::string& path);
    std::string Request(const std::string& request);
    unsigned int GetLoadCount();
    ~TSaoriBindingLnx();
private:
    struct State;
    State* state;
    explicit TSaoriBindingLnx(State* s) : state(s) {}
    TSaoriBindingLnx(const TSaoriBindingLnx&);
    TSaoriBindingLnx& operator=(const TSaoriBindingLnx&);
};
