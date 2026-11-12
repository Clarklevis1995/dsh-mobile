#import "VoiceAudioExceptionGuard.h"

NSError *DSHCatchVoiceAudioException(void (NS_NOESCAPE ^operation)(void)) {
  @try {
    operation();
    return nil;
  } @catch (NSException *exception) {
    return [NSError errorWithDomain:@"VoiceAudioEngine"
                              code:1
                          userInfo:@{
                            NSLocalizedDescriptionKey : @"音频输入当前不可用",
                            @"exceptionName" : exception.name
                          }];
  }
}
