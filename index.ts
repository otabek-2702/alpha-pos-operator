import { registerRootComponent } from 'expo';

import App from './App';

// registerRootComponent calls AppRegistry.registerComponent('main', () => App)
// and wires the root view correctly for both Expo Go and dev/standalone builds.
registerRootComponent(App);
