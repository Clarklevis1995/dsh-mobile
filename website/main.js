(() => {
  const root = document.documentElement;
  const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)');
  const header = document.querySelector('.header');
  const menuButton = document.querySelector('.menu-toggle');
  const closeMenu = () => {
    menuButton.setAttribute('aria-expanded', 'false');
    header.classList.remove('nav-open');
  };
  menuButton.addEventListener('click', () => {
    const open = menuButton.getAttribute('aria-expanded') !== 'true';
    menuButton.setAttribute('aria-expanded', String(open));
    header.classList.toggle('nav-open', open);
  });
  document.querySelectorAll('.navigation a').forEach(link => link.addEventListener('click', closeMenu));
  document.addEventListener('keydown', event => { if (event.key === 'Escape') closeMenu(); });

  const supportDialog = document.getElementById('support-dialog');
  let supportTrigger;
  document.querySelectorAll('[data-support-dialog]').forEach(button => {
    button.addEventListener('click', () => {
      supportTrigger = button;
      closeMenu();
      if (typeof supportDialog.showModal === 'function') supportDialog.showModal();
      else window.open(supportDialog.querySelector('img').src, '_blank', 'noopener');
    });
  });
  supportDialog.addEventListener('click', event => {
    const bounds = supportDialog.getBoundingClientRect();
    if (event.target === supportDialog && (event.clientX < bounds.left || event.clientX > bounds.right || event.clientY < bounds.top || event.clientY > bounds.bottom)) supportDialog.close();
  });
  supportDialog.addEventListener('close', () => {
    // A collapsed mobile menu hides its trigger; return focus to the menu button.
    const target = supportTrigger?.closest('.navigation') && getComputedStyle(document.querySelector('.navigation')).display === 'none' ? menuButton : supportTrigger;
    target?.focus();
  });

  const features = {
    conversation: { image: 'conversation', name: '实时对话', alt: 'Whaleness 实时对话，展示消息、工具执行结果和输入区域' },
    trajectory: { image: 'trajectory', name: '执行轨迹', alt: 'Whaleness 执行轨迹，展示耗时概览和模型、工具事件时间线' },
    plugins: { image: 'plugins', name: '插件管理', alt: 'Whaleness 插件列表，展示已连接主机上的官方插件和启用状态' },
    tasks: { image: 'tasks', name: '定时任务', alt: 'Whaleness 定时任务页面，展示执行规则、计划时间和任务状态' },
  };
  const tabs = [...document.querySelectorAll('.showcase [role="tab"]')];
  const image = document.getElementById('showcase-image');
  const screenshotButton = document.querySelector('.screenshot-button');
  const selectFeature = tab => {
    tabs.forEach(candidate => {
      const selected = candidate === tab;
      candidate.setAttribute('aria-selected', String(selected));
      candidate.tabIndex = selected ? 0 : -1;
      const panel = document.getElementById(candidate.getAttribute('aria-controls'));
      panel.hidden = !selected;
      panel.classList.toggle('is-entering', selected);
    });
    const feature = features[tab.dataset.feature];
    image.src = `website/assets/mockups/${feature.image}.webp`;
    image.dataset.originalSrc = `website/assets/${feature.image}.webp`;
    image.alt = feature.alt;
    screenshotButton.setAttribute('aria-label', `放大查看${feature.name}截图`);
  };
  tabs.forEach((tab, index) => {
    tab.addEventListener('click', () => selectFeature(tab));
    tab.addEventListener('keydown', event => {
      let next = index;
      if (event.key === 'ArrowRight') next = (index + 1) % tabs.length;
      else if (event.key === 'ArrowLeft') next = (index - 1 + tabs.length) % tabs.length;
      else if (event.key === 'Home') next = 0;
      else if (event.key === 'End') next = tabs.length - 1;
      else return;
      event.preventDefault();
      selectFeature(tabs[next]);
      tabs[next].focus();
    });
  });

  const dialog = document.getElementById('screenshot-dialog');
  const dialogImage = document.getElementById('dialog-image');
  const openScreenshot = (sourceImage, wide = false) => {
    const originalSrc = sourceImage.dataset.originalSrc || sourceImage.src;
    dialogImage.src = originalSrc;
    dialogImage.alt = sourceImage.alt;
    document.getElementById('dialog-original').href = originalSrc;
    dialog.classList.toggle('is-wide', wide);
    if (typeof dialog.showModal === 'function') dialog.showModal();
    else window.open(originalSrc, '_blank', 'noopener');
  };
  screenshotButton.addEventListener('click', () => openScreenshot(image));
  dialog.addEventListener('click', event => {
    const bounds = dialog.getBoundingClientRect();
    if (event.target === dialog && (event.clientX < bounds.left || event.clientX > bounds.right || event.clientY < bounds.top || event.clientY > bounds.bottom)) dialog.close();
  });

  const setupSteps = [
    [{ file: 'desktop', label: '桌面端', alt: 'DeepSeek Harness 桌面端，左侧栏上方有插件入口，左下角有移动设备入口', caption: '桌面端左侧栏的“插件”入口。' }],
    [
      { file: 'plugins', label: '插件列表', alt: '桌面端插件列表，右上角有添加插件按钮', caption: '点击插件列表右上角的“添加插件”。' },
      { file: 'add-plugin', label: '添加窗口', alt: '添加插件窗口，输入框支持填写插件包名', caption: '在输入框中填写 dsh-plugin-mobile-gateway，再点击“安装”。' },
    ],
    [{ file: 'enabled', label: '启用状态', alt: '移动网关插件详情，插件与组件开关开启，组件显示运行中', caption: '确认插件总开关与 mobile-gateway 组件开关均已开启。' }],
    [{ file: 'gateway', label: '通用设置', alt: '移动设备通用设置，网关常驻开启，设备鉴权已开启', caption: '移动设备面板中的“通用设置”。' }],
    [
      { file: 'pairing', label: '连接与配对', alt: '移动设备连接与配对页面，包含配对连接方式和生成配对二维码按钮', caption: '打开“连接与配对”，选择合适的连接方式。' },
      { file: 'connection-modes', label: '连接方式选项', alt: '配对连接方式菜单，提供局域网直连、Cloudflare 外网与手动输入地址', caption: '同一 Wi-Fi 选择“局域网直连”，外网连接选择已配置的“Cloudflare 外网”。' },
    ],
    [{ file: 'pairing', label: '生成配对码', alt: '连接与配对页面中的生成配对二维码按钮', caption: '点击“生成配对二维码”，然后用手机扫描自己桌面端弹出的二维码。' }],
  ];
  const stepButtons = [...document.querySelectorAll('[data-setup-step]')];
  const setupImage = document.getElementById('setup-image');
  const setupScreenshot = document.getElementById('setup-screenshot');
  const setupCaption = document.getElementById('setup-caption');
  const setupViews = document.getElementById('setup-views');
  const setupContent = document.getElementById('setup-content');
  const previousStep = document.getElementById('setup-previous');
  const nextStep = document.getElementById('setup-next');
  let currentStep = 0;
  let setupAnimations = [];
  let outgoingContent = null;
  const cancelSetupTransition = () => {
    setupAnimations.forEach(animation => animation.cancel());
    setupAnimations = [];
    outgoingContent?.remove();
    outgoingContent = null;
  };
  const transitionSetup = update => {
    cancelSetupTransition();
    if (reduceMotion.matches || typeof setupContent.animate !== 'function') {
      update();
      return;
    }
    // Keep navigation outside the crossfade so it remains immediately clickable.
    const previousContent = setupContent.cloneNode(true);
    previousContent.removeAttribute('id');
    previousContent.querySelectorAll('[id]').forEach(element => element.removeAttribute('id'));
    previousContent.classList.add('setup-transition-layer');
    previousContent.setAttribute('aria-hidden', 'true');
    previousContent.inert = true;
    update();
    setupContent.parentElement.appendChild(previousContent);
    outgoingContent = previousContent;
    const fadeOut = previousContent.animate([{ opacity: 1 }, { opacity: 0 }], { duration: 160, easing: 'ease-out', fill: 'forwards' });
    const fadeIn = setupContent.animate([{ opacity: 0 }, { opacity: 1 }], { duration: 240, easing: 'ease-out' });
    setupAnimations = [fadeOut, fadeIn];
    fadeOut.onfinish = () => {
      previousContent.remove();
      if (outgoingContent === previousContent) outgoingContent = null;
    };
    fadeIn.onfinish = () => {
      if (setupAnimations.includes(fadeIn)) setupAnimations = [];
    };
  };
  reduceMotion.addEventListener('change', () => { if (reduceMotion.matches) cancelSetupTransition(); });
  // All captions contribute to the reserved height without being announced.
  const captions = new Set(setupSteps.flat().map(view => view.caption));
  captions.forEach(caption => {
    const sizing = document.createElement('span');
    sizing.className = 'setup-caption-sizing';
    sizing.setAttribute('aria-hidden', 'true');
    sizing.textContent = caption;
    setupCaption.parentElement.appendChild(sizing);
  });
  const selectSetupImage = view => {
    setupImage.src = `website/assets/setup/${view.file}.webp`;
    setupImage.alt = view.alt;
    setupCaption.textContent = view.caption;
    setupScreenshot.setAttribute('aria-label', `放大查看${view.label}截图`);
  };
  const selectSetupStep = index => {
    currentStep = index;
    stepButtons.forEach((button, buttonIndex) => {
      const selected = buttonIndex === index;
      if (selected) button.setAttribute('aria-current', 'step');
      else button.removeAttribute('aria-current');
      document.getElementById(button.getAttribute('aria-controls')).hidden = !selected;
    });
    setupViews.replaceChildren();
    const views = setupSteps[index];
    views.forEach((view, viewIndex) => {
      if (views.length === 1) return;
      const button = document.createElement('button');
      button.type = 'button';
      button.textContent = view.label;
      button.setAttribute('aria-pressed', String(viewIndex === 0));
      button.addEventListener('click', () => {
        if (button.getAttribute('aria-pressed') === 'true') return;
        transitionSetup(() => {
          [...setupViews.children].forEach(candidate => candidate.setAttribute('aria-pressed', String(candidate === button)));
          selectSetupImage(view);
        });
      });
      setupViews.appendChild(button);
    });
    setupViews.hidden = views.length === 1;
    selectSetupImage(views[0]);
    previousStep.disabled = index === 0;
    nextStep.disabled = index === setupSteps.length - 1;
  };
  const changeSetupStep = index => {
    if (index !== currentStep) transitionSetup(() => selectSetupStep(index));
  };
  stepButtons.forEach((button, index) => button.addEventListener('click', () => changeSetupStep(index)));
  previousStep.addEventListener('click', () => { if (currentStep > 0) changeSetupStep(currentStep - 1); });
  nextStep.addEventListener('click', () => { if (currentStep < setupSteps.length - 1) changeSetupStep(currentStep + 1); });
  setupScreenshot.addEventListener('click', () => openScreenshot(setupImage, true));
  selectSetupStep(0);

  if ('IntersectionObserver' in window && !reduceMotion.matches) {
    root.classList.add('js');
    const observer = new IntersectionObserver(entries => {
      entries.forEach(entry => {
        if (entry.isIntersecting) {
          entry.target.classList.add('is-visible');
          observer.unobserve(entry.target);
        }
      });
    }, { threshold: .12 });
    document.querySelectorAll('.reveal').forEach(element => observer.observe(element));
    reduceMotion.addEventListener('change', () => { if (reduceMotion.matches) root.classList.remove('js'); });
  }
})();
