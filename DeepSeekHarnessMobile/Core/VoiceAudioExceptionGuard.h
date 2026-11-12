#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

// AVAudioEngine 的配置断言以 NSException 抛出，Swift do/catch 无法捕获。
FOUNDATION_EXPORT NSError * _Nullable DSHCatchVoiceAudioException(
    void (NS_NOESCAPE ^operation)(void));

NS_ASSUME_NONNULL_END
