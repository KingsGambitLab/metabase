import { type ReactNode, type JSX, useEffect } from "react";
import { memo } from "react";
import { Provider } from "react-redux";

import { AppInitializeController } from "embedding-sdk/components/private/AppInitializeController";
import {} from "embedding-sdk/components/private/PublicComponentWrapper";
import type { SdkPluginsConfig } from "embedding-sdk/lib/plugins";
import { store } from "embedding-sdk/store";
import { ChartThemeOverride } from "embedding-sdk/theme/ChartThemeOverride";
import type { MetabaseTheme } from "embedding-sdk/theme/types";
import {
  setErrorComponent,
  setLoaderComponent,
  setPlugins,
} from "embedding-sdk/store/reducer";
import type { SDKConfig } from "embedding-sdk/types";
import { EmotionCacheProvider } from "metabase/styled-components/components/EmotionCacheProvider";
import { ThemeProvider } from "metabase/ui/components/theme/ThemeProvider";

import "metabase/css/vendor.css";
import "metabase/css/index.module.css";

interface MetabaseProviderProps {
  children: ReactNode;
  config: SDKConfig;
  pluginsConfig?: SdkPluginsConfig;
  theme?: MetabaseTheme;
}

const MetabaseProviderInternal = ({
  children,
  config,
  pluginsConfig,
  theme,
}: MetabaseProviderProps): JSX.Element => {
  useEffect(() => {
    store.dispatch(setPlugins(pluginsConfig || null));
  }, [pluginsConfig]);

  useEffect(() => {
    store.dispatch(setLoaderComponent(config.loaderComponent ?? null));
  }, [config.loaderComponent]);

  useEffect(() => {
    store.dispatch(setErrorComponent(config.errorComponent ?? null));
  }, [config.errorComponent]);

  return (
    <Provider store={store}>
      <EmotionCacheProvider>
        <ThemeProvider theme={theme}>
          <ChartThemeOverride>
            <AppInitializeController config={config}>
              {children}
            </AppInitializeController>
          </ChartThemeOverride>
        </ThemeProvider>
      </EmotionCacheProvider>
    </Provider>
  );
};

export const MetabaseProvider = memo(MetabaseProviderInternal);
