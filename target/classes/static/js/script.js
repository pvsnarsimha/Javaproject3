document.querySelectorAll('.quick-actions button').forEach(button => {
    button.addEventListener('click', function() {
        const megaMenu = this.nextElementSibling;
        megaMenu.style.display = megaMenu.style.display === 'block' ? 'none' : 'block';
    });
});