require 'xcodeproj'

root = File.expand_path('..', __dir__)
path = File.join(root, 'DeepSeekHarnessMobile.xcodeproj')
project = Xcodeproj::Project.new(path)
project.root_object.attributes['LastSwiftUpdateCheck'] = '2660'
project.root_object.attributes['LastUpgradeCheck'] = '2660'

app = project.new_target(:application, 'DeepSeekHarnessMobile', :ios, '17.0')
tests = project.new_target(:unit_test_bundle, 'DeepSeekHarnessMobileTests', :ios, '17.0')
tests.add_dependency(app)

markdown_package = project.new(Xcodeproj::Project::Object::XCLocalSwiftPackageReference)
markdown_package.relative_path = 'Vendor/swift-markdown-ui'
project.root_object.package_references << markdown_package

markdown_product = project.new(Xcodeproj::Project::Object::XCSwiftPackageProductDependency)
markdown_product.package = markdown_package
markdown_product.product_name = 'MarkdownUI'
app.package_product_dependencies << markdown_product

markdown_build_file = project.new(Xcodeproj::Project::Object::PBXBuildFile)
markdown_build_file.product_ref = markdown_product
app.frameworks_build_phase.files << markdown_build_file

app_group = project.main_group.new_group('DeepSeekHarnessMobile', 'DeepSeekHarnessMobile')
%w[App Core Components Views].each do |folder|
  group = app_group.new_group(folder, folder)
  Dir[File.join(root, 'DeepSeekHarnessMobile', folder, '*.swift')].sort.each do |file|
    ref = group.new_file(File.basename(file))
    app.source_build_phase.add_file_reference(ref)
  end
end
shaders = app_group.new_group('Shaders', 'Shaders')
Dir[File.join(root, 'DeepSeekHarnessMobile', 'Shaders', '*.metal')].sort.each do |file|
  ref = shaders.new_file(File.basename(file))
  app.source_build_phase.add_file_reference(ref)
end
resources = app_group.new_group('Resources', 'Resources')
resources.new_file('Info.plist')
assets = resources.new_file('Assets.xcassets')
app.resources_build_phase.add_file_reference(assets)

test_group = project.main_group.new_group('DeepSeekHarnessMobileTests', 'DeepSeekHarnessMobileTests')
Dir[File.join(root, 'DeepSeekHarnessMobileTests', '*.swift')].sort.each do |file|
  ref = test_group.new_file(File.basename(file))
  tests.source_build_phase.add_file_reference(ref)
end

app.build_configurations.each do |config|
  config.build_settings['PRODUCT_NAME'] = 'DshMobile'
  config.build_settings['PRODUCT_MODULE_NAME'] = 'DeepSeekHarnessMobile'
  config.build_settings['PRODUCT_BUNDLE_IDENTIFIER'] = 'ai.dsh.mobile.ios'
  config.build_settings['INFOPLIST_FILE'] = 'DeepSeekHarnessMobile/Resources/Info.plist'
  config.build_settings['ASSETCATALOG_COMPILER_APPICON_NAME'] = 'AppIcon'
  config.build_settings['ASSETCATALOG_COMPILER_GLOBAL_ACCENT_COLOR_NAME'] = 'AccentColor'
  config.build_settings['SWIFT_VERSION'] = '5.10'
  config.build_settings['TARGETED_DEVICE_FAMILY'] = '1'
  config.build_settings['CODE_SIGN_STYLE'] = 'Automatic'
  config.build_settings['GENERATE_INFOPLIST_FILE'] = 'NO'
end
tests.build_configurations.each do |config|
  config.build_settings['PRODUCT_BUNDLE_IDENTIFIER'] = 'ai.dsh.mobile.ios.tests'
  config.build_settings['SWIFT_VERSION'] = '5.10'
  config.build_settings['GENERATE_INFOPLIST_FILE'] = 'YES'
  config.build_settings['TEST_HOST'] = '$(BUILT_PRODUCTS_DIR)/DshMobile.app/$(BUNDLE_EXECUTABLE_FOLDER_PATH)/DshMobile'
  config.build_settings['BUNDLE_LOADER'] = '$(TEST_HOST)'
end

project.save

scheme = Xcodeproj::XCScheme.new
scheme.add_build_target(app)
scheme.add_test_target(tests)
scheme.set_launch_target(app)
scheme.save_as(path, 'DeepSeekHarnessMobile', true)
