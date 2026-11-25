require 'json'

package = JSON.parse(File.read(File.join(__dir__, 'package.json')))

Pod::Spec.new do |s|
  s.name = 'CapacitorCodeplayWifiTvRemote'
  s.version = package['version']
  s.summary = 'Capacitor Android TV remote plugin'
  s.license = { :type => 'MIT' }
  s.author = 'Codeplay'
  s.homepage = 'https://github.com/codeplay'
  s.source = { :git => 'https://github.com/codeplay/capacitor-codeplay-wifi-tv-remote.git', :tag => s.version.to_s }
  s.source_files = 'ios/Plugin/**/*.{swift,m}'
  s.ios.deployment_target = '15.0'
  s.dependency 'Capacitor'
  s.swift_version = '5.9'
end
