#include <napi/native_api.h>

#include <cstdint>
#include <string>
#include <vector>

#include "libwhaleness_api.h"

namespace {
struct Engine {
  libwhaleness_KLong handle;
};

void Finalize(napi_env, void* data, void*) {
  auto* engine = static_cast<Engine*>(data);
  if (engine != nullptr) {
    if (engine->handle != 0) {
      libwhaleness_symbols()->kotlin.root.whaleness.destroy(engine->handle);
    }
    delete engine;
  }
}

napi_value Construct(napi_env env, napi_callback_info info) {
  napi_value self;
  napi_value new_target;
  napi_get_new_target(env, info, &new_target);
  if (new_target == nullptr) {
    napi_throw_type_error(env, nullptr, "Use new Engine()");
    return nullptr;
  }
  napi_get_cb_info(env, info, nullptr, nullptr, &self, nullptr);
  auto* engine =
      new Engine{libwhaleness_symbols()->kotlin.root.whaleness.create()};
  if (napi_wrap(env, self, engine, Finalize, nullptr, nullptr) != napi_ok) {
    Finalize(env, engine, nullptr);
    napi_throw_error(env, nullptr, "Cannot initialize shared engine");
    return nullptr;
  }
  return self;
}

napi_value Dispatch(napi_env env, napi_callback_info info) {
  size_t argc = 1;
  napi_value args[1];
  napi_value self;
  napi_get_cb_info(env, info, &argc, args, &self, nullptr);
  Engine* engine = nullptr;
  if (napi_unwrap(env, self, reinterpret_cast<void**>(&engine)) != napi_ok ||
      engine == nullptr || engine->handle == 0) {
    napi_throw_type_error(env, nullptr, "Invalid shared engine");
    return nullptr;
  }
  size_t length = 0;
  if (argc != 1 ||
      napi_get_value_string_utf8(env, args[0], nullptr, 0, &length) !=
          napi_ok ||
      length > 16 * 1024 * 1024) {
    napi_throw_type_error(env, nullptr, "Expected a bounded JSON command");
    return nullptr;
  }
  std::vector<char> input(length + 1);
  napi_get_value_string_utf8(env, args[0], input.data(), input.size(), &length);
  const auto* api = libwhaleness_symbols();
  const char* response =
      api->kotlin.root.whaleness.dispatch(engine->handle, input.data());
  napi_value result;
  const napi_status status =
      napi_create_string_utf8(env, response, NAPI_AUTO_LENGTH, &result);
  api->DisposeString(response);
  if (status != napi_ok) {
    napi_throw_error(env, nullptr, "Cannot encode shared response");
    return nullptr;
  }
  return result;
}

napi_value Close(napi_env env, napi_callback_info info) {
  napi_value self;
  napi_get_cb_info(env, info, nullptr, nullptr, &self, nullptr);
  Engine* engine = nullptr;
  if (napi_unwrap(env, self, reinterpret_cast<void**>(&engine)) != napi_ok ||
      engine == nullptr) {
    napi_throw_type_error(env, nullptr, "Invalid shared engine");
    return nullptr;
  }
  if (engine->handle != 0) {
    const auto handle = engine->handle;
    engine->handle = 0;
    libwhaleness_symbols()->kotlin.root.whaleness.destroy(handle);
  }
  napi_value result;
  napi_get_undefined(env, &result);
  return result;
}

napi_value RenderContent(napi_env env, napi_callback_info info) {
  size_t argc = 1;
  napi_value args[1];
  napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
  size_t length = 0;
  if (argc != 1 ||
      napi_get_value_string_utf8(env, args[0], nullptr, 0, &length) !=
          napi_ok ||
      length > 4 * 1024 * 1024) {
    napi_throw_type_error(env, nullptr, "Expected bounded render input");
    return nullptr;
  }
  std::vector<char> input(length + 1);
  napi_get_value_string_utf8(env, args[0], input.data(), input.size(), &length);
  const auto* api = libwhaleness_symbols();
  const char* response = api->kotlin.root.whaleness.renderContent(input.data());
  napi_value result;
  const napi_status status =
      napi_create_string_utf8(env, response, NAPI_AUTO_LENGTH, &result);
  api->DisposeString(response);
  if (status != napi_ok) {
    napi_throw_error(env, nullptr, "Cannot encode rendered content");
    return nullptr;
  }
  return result;
}

napi_value Init(napi_env env, napi_value exports) {
  napi_property_descriptor methods[] = {
      {"dispatch", nullptr, Dispatch, nullptr, nullptr, nullptr, napi_default,
       nullptr},
      {"close", nullptr, Close, nullptr, nullptr, nullptr, napi_default,
       nullptr},
  };
  napi_value constructor;
  napi_define_class(env, "Engine", NAPI_AUTO_LENGTH, Construct, nullptr, 2,
                    methods, &constructor);
  napi_set_named_property(env, exports, "Engine", constructor);
  napi_property_descriptor render = {"renderContent", nullptr, RenderContent,
                                     nullptr,         nullptr, nullptr,
                                     napi_default,    nullptr};
  napi_define_properties(env, exports, 1, &render);
  return exports;
}

napi_module module = {1,       0,        nullptr, Init, "whaleness_bridge",
                      nullptr, {nullptr}};
__attribute__((constructor)) void Register() { napi_module_register(&module); }
}  // namespace
